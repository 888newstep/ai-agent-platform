[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DatasetPath,
    [ValidateSet("unknown", "sample", "smoke", "llm-assisted-silver", "synthetic", "independent-human-labeled")]
    [string]$DatasetKind = "unknown",
    [ValidateRange(1, 100000)]
    [int]$MinCases = 1,
    [ValidateRange(0.0, 1.0)]
    [double]$MinNegativeRatio = 0.0,
    [ValidateRange(1, 1000)]
    [int]$MinCategories = 1,
    [switch]$AsJson
)

$ErrorActionPreference = "Stop"

function Get-PropertyValue {
    param([object]$Item, [string]$Name)
    $property = $Item.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

$resolvedPath = [IO.Path]::GetFullPath($DatasetPath)
if (-not (Test-Path -LiteralPath $resolvedPath -PathType Leaf)) {
    throw "Dataset file not found: $resolvedPath"
}
if ([IO.Path]::GetExtension($resolvedPath) -ne ".json") {
    throw "Evidence dataset must be a JSON file."
}

$parsed = Get-Content -LiteralPath $resolvedPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($parsed -isnot [Collections.IEnumerable] -or $parsed -is [string]) {
    throw "Evidence dataset must be a JSON array."
}

$cases = @($parsed)
$errors = [Collections.Generic.List[string]]::new()
$warnings = [Collections.Generic.List[string]]::new()
$categories = [ordered]@{}
$caseKeys = @{}
$negativeCount = 0

if ($cases.Count -lt $MinCases) {
    $errors.Add("caseCount=$($cases.Count) is below MinCases=$MinCases")
}

for ($index = 0; $index -lt $cases.Count; $index++) {
    $case = $cases[$index]
    $question = ([string](Get-PropertyValue $case "question")).Trim()
    $evidence = ([string](Get-PropertyValue $case "evidence")).Trim()
    $category = ([string](Get-PropertyValue $case "category")).Trim()
    $keywords = @(Get-PropertyValue $case "keywords")
    $expectedProperty = $case.PSObject.Properties["expectedSupported"]

    if ([string]::IsNullOrWhiteSpace($question)) { $errors.Add("case[$index] question is blank") }
    if ([string]::IsNullOrWhiteSpace($evidence)) { $errors.Add("case[$index] evidence is blank") }
    if ($keywords.Count -eq 0 -or @($keywords | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }).Count -eq 0) {
        $errors.Add("case[$index] keywords must contain at least one value")
    }
    if ($null -eq $expectedProperty -or $expectedProperty.Value -isnot [bool]) {
        $errors.Add("case[$index] expectedSupported must be boolean")
    } elseif (-not [bool]$expectedProperty.Value) {
        $negativeCount++
    }
    if ([string]::IsNullOrWhiteSpace($category)) {
        $errors.Add("case[$index] category is required")
        $category = "uncategorized"
    }
    if (-not $categories.Contains($category)) { $categories[$category] = 0 }
    $categories[$category]++

    $key = (($question -replace "\s+", " ").ToLowerInvariant() + "\u001f" +
            ($evidence -replace "\s+", " ").ToLowerInvariant())
    if ($caseKeys.ContainsKey($key)) {
        $errors.Add("case[$index] duplicates case[$($caseKeys[$key])]")
    } else {
        $caseKeys[$key] = $index
    }
}

$negativeRatio = if ($cases.Count -eq 0) { 0.0 } else { $negativeCount / [double]$cases.Count }
if ($negativeRatio -lt $MinNegativeRatio) {
    $errors.Add("negativeRatio=$([Math]::Round($negativeRatio, 4)) is below MinNegativeRatio=$MinNegativeRatio")
}
if ($categories.Count -lt $MinCategories) {
    $errors.Add("categoryCount=$($categories.Count) is below MinCategories=$MinCategories")
}
if ($DatasetKind -eq "llm-assisted-silver") {
    $warnings.Add("LLM consensus is a silver label and does not replace independent human review")
}
if ($DatasetKind -eq "synthetic") {
    $warnings.Add("synthetic evidence is suitable for regression testing, not production-quality claims")
}

$summary = [ordered]@{
    datasetFileName = [IO.Path]::GetFileName($resolvedPath)
    datasetKind = $DatasetKind
    structuralValidationPassed = $errors.Count -eq 0
    manualReviewRequired = $DatasetKind -ne "independent-human-labeled"
    caseCount = $cases.Count
    positiveCount = $cases.Count - $negativeCount
    negativeCount = $negativeCount
    negativeRatio = [Math]::Round($negativeRatio, 4)
    categoryCount = $categories.Count
    categoryCounts = $categories
    errors = @($errors)
    warnings = @($warnings)
}

if ($AsJson) {
    $summary | ConvertTo-Json -Depth 10
} else {
    Write-Output "Dataset: $($summary.datasetFileName) ($DatasetKind)"
    Write-Output "Structural validation: $($summary.structuralValidationPassed)"
    Write-Output "Cases: $($cases.Count); negatives: $negativeCount ($([Math]::Round($negativeRatio * 100, 2))%); categories: $($categories.Count)"
    $errors | ForEach-Object { Write-Output "[ERROR] $_" }
    $warnings | ForEach-Object { Write-Output "[WARN] $_" }
}

if ($errors.Count -gt 0) { exit 1 }
