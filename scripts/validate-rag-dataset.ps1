[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DatasetPath,
    [ValidateSet("unknown", "sample", "smoke", "llm-assisted-silver", "synthetic", "independent-human-labeled")]
    [string]$DatasetKind = "unknown",
    [int]$MinCases = 1,
    [int]$MinCategories = 1,
    [switch]$RequireCategory,
    [switch]$AsJson
)

$ErrorActionPreference = "Stop"

if ($MinCases -lt 1) {
    throw "MinCases must be greater than zero."
}
if ($MinCategories -lt 1) {
    throw "MinCategories must be greater than zero."
}

function Get-PropertyValue {
    param(
        [object]$Item,
        [string[]]$Names
    )

    foreach ($name in $Names) {
        $property = $Item.PSObject.Properties[$name]
        if ($null -ne $property) {
            return $property.Value
        }
    }
    return $null
}

function Get-RelevantDocIds {
    param([object]$RawValue)

    $result = [System.Collections.Generic.List[string]]::new()
    if ($null -eq $RawValue) {
        return @()
    }

    if ($RawValue -is [System.Collections.IEnumerable] -and $RawValue -isnot [string]) {
        foreach ($item in $RawValue) {
            $itemText = ([string]$item).Trim()
            if (-not [string]::IsNullOrWhiteSpace($itemText) -and -not $result.Contains($itemText)) {
                [void]$result.Add($itemText)
            }
        }
        return @($result)
    }

    $text = ([string]$RawValue).Trim()
    if ([string]::IsNullOrWhiteSpace($text)) {
        return @()
    }

    if ($text.StartsWith("[") -and $text.EndsWith("]")) {
        try {
            $parsed = $text | ConvertFrom-Json
            foreach ($item in $parsed) {
                $itemText = ([string]$item).Trim()
                if (-not [string]::IsNullOrWhiteSpace($itemText) -and -not $result.Contains($itemText)) {
                    [void]$result.Add($itemText)
                }
            }
            return @($result)
        } catch {
            # Fall back to delimiter parsing and let structural validation decide.
        }
    }

    foreach ($part in ($text -split '[|;]')) {
        $partText = $part.Trim()
        if (-not [string]::IsNullOrWhiteSpace($partText) -and -not $result.Contains($partText)) {
            [void]$result.Add($partText)
        }
    }
    return @($result)
}

function Read-DatasetCases {
    param([string]$Path)

    $extension = [System.IO.Path]::GetExtension($Path).ToLowerInvariant()
    if ($extension -eq ".json") {
        $parsed = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($parsed -isnot [System.Collections.IEnumerable] -or $parsed -is [string]) {
            throw "JSON dataset must be an array of cases."
        }
        $cases = @()
        foreach ($item in $parsed) {
            $cases += [pscustomobject]@{
                question = [string](Get-PropertyValue -Item $item -Names @("question"))
                relevantDocIds = @(Get-RelevantDocIds -RawValue (Get-PropertyValue -Item $item -Names @("relevantDocIds", "relevant_doc_ids")))
                category = [string](Get-PropertyValue -Item $item -Names @("category", "type"))
            }
        }
        return $cases
    }

    if ($extension -eq ".csv") {
        $rows = Import-Csv -LiteralPath $Path -Encoding UTF8
        $cases = @()
        foreach ($item in $rows) {
            $cases += [pscustomobject]@{
                question = [string](Get-PropertyValue -Item $item -Names @("question"))
                relevantDocIds = @(Get-RelevantDocIds -RawValue (Get-PropertyValue -Item $item -Names @("relevantDocIds", "relevant_doc_ids")))
                category = [string](Get-PropertyValue -Item $item -Names @("category", "type"))
            }
        }
        return $cases
    }

    throw "Unsupported dataset format. Use .json or .csv."
}

$resolvedPath = [System.IO.Path]::GetFullPath($DatasetPath)
if (-not (Test-Path -LiteralPath $resolvedPath -PathType Leaf)) {
    throw "Dataset file not found."
}

$cases = @(Read-DatasetCases -Path $resolvedPath)
$errors = [System.Collections.Generic.List[string]]::new()
$warnings = [System.Collections.Generic.List[string]]::new()
$categoryCounts = [ordered]@{}
$questionKeys = @{}
$duplicateQuestionCount = 0
$duplicateIdsWithinCaseCount = 0
$mustHaveCategory = $RequireCategory.IsPresent -or $DatasetKind -eq "independent-human-labeled"

if ($cases.Count -lt $MinCases) {
    $errors.Add("caseCount=$($cases.Count) is below MinCases=$MinCases")
}

for ($index = 0; $index -lt $cases.Count; $index++) {
    $case = $cases[$index]
    $question = ([string]$case.question).Trim()
    $ids = @($case.relevantDocIds)
    $category = ([string]$case.category).Trim()

    if ([string]::IsNullOrWhiteSpace($question)) {
        $errors.Add("case[$index] question is blank")
    } else {
        $questionKey = ($question -replace "\s+", " ").ToLowerInvariant()
        if ($questionKeys.ContainsKey($questionKey)) {
            $duplicateQuestionCount++
        } else {
            $questionKeys[$questionKey] = $index
        }
    }

    if ($ids.Count -eq 0) {
        $errors.Add("case[$index] must contain at least one relevantDocId")
    } elseif (@($ids | Select-Object -Unique).Count -ne $ids.Count) {
        $duplicateIdsWithinCaseCount++
    }

    if ($mustHaveCategory -and [string]::IsNullOrWhiteSpace($category)) {
        $errors.Add("case[$index] category is required")
        $category = "uncategorized"
    } elseif ([string]::IsNullOrWhiteSpace($category)) {
        $category = "uncategorized"
    }

    if (-not $categoryCounts.Contains($category)) {
        $categoryCounts[$category] = 0
    }
    $categoryCounts[$category]++
}

if ($duplicateQuestionCount -gt 0) {
    $warnings.Add("duplicate normalized questions: $duplicateQuestionCount")
}
if ($duplicateIdsWithinCaseCount -gt 0) {
    $warnings.Add("cases containing duplicate relevantDocIds: $duplicateIdsWithinCaseCount")
}
if ($categoryCounts.Count -lt $MinCategories) {
    $errors.Add("categoryCount=$($categoryCounts.Count) is below MinCategories=$MinCategories")
}
if ($DatasetKind -eq "sample" -or $DatasetKind -eq "smoke") {
    $warnings.Add("datasetKind=$DatasetKind is diagnostic data and must not be used as a formal quality conclusion")
}
if ($DatasetKind -eq "llm-assisted-silver") {
    $warnings.Add("LLM-assisted silver labels require manual audit before they can support business-quality claims")
}
if ($DatasetKind -eq "synthetic") {
    $warnings.Add("synthetic data may be used for regression and boundary testing, not production-quality claims")
}
if ($DatasetKind -eq "independent-human-labeled") {
    $warnings.Add("structural validation cannot prove annotation independence, source quality, or annotator agreement")
}
$warnings.Add("this structural check does not query Milvus; run validate-rag-milvus-ids.ps1 separately")

$summary = [ordered]@{}
$summary.datasetFileName = [System.IO.Path]::GetFileName($resolvedPath)
$summary.datasetKind = $DatasetKind
$summary.structuralValidationPassed = $errors.Count -eq 0
$summary.manualReviewRequired = $true
$summary.caseCount = $cases.Count
$summary.categoryCount = $categoryCounts.Count
$summary.categoryCounts = $categoryCounts
$summary.duplicateQuestionCount = $duplicateQuestionCount
$summary.duplicateIdsWithinCaseCount = $duplicateIdsWithinCaseCount
$summary.errors = @($errors)
$summary.warnings = @($warnings)

if ($AsJson) {
    $summary | ConvertTo-Json -Depth 10
} else {
    Write-Output ("Dataset: {0} ({1})" -f $summary.datasetFileName, $summary.datasetKind)
    Write-Output ("Structural validation: {0}" -f $summary.structuralValidationPassed)
    Write-Output ("Cases: {0}; categories: {1}" -f $summary.caseCount, $summary.categoryCount)
    foreach ($errorMessage in $errors) {
        Write-Output ("[ERROR] {0}" -f $errorMessage)
    }
    foreach ($warning in $warnings) {
        Write-Output ("[WARN] {0}" -f $warning)
    }
}

if ($errors.Count -gt 0) {
    exit 1
}
