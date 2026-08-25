[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$AuditPath,
    [ValidateRange(1, 200)]
    [int]$SourceCount = 30,
    [ValidateRange(1, 8)]
    [int]$MinCategories = 6,
    [string]$OutputDirectory
)

$ErrorActionPreference = "Stop"

function Normalize-Text {
    param([string]$Value)
    if ($null -eq $Value) { return "" }
    return (($Value.Trim() -replace "\s+", " ").ToLowerInvariant())
}

function Write-JsonFile {
    param([string]$Path, [object]$Value)
    [IO.File]::WriteAllText(
        $Path,
        ($Value | ConvertTo-Json -Depth 30),
        [Text.UTF8Encoding]::new($false))
}

function Get-FileSha256 {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

$resolvedAuditPath = [IO.Path]::GetFullPath($AuditPath)
if (-not (Test-Path -LiteralPath $resolvedAuditPath -PathType Leaf)) {
    throw "Audit file not found: $resolvedAuditPath"
}
$resolvedOutputDirectory = if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    Split-Path -Parent $resolvedAuditPath
} else {
    [IO.Path]::GetFullPath($OutputDirectory)
}
New-Item -ItemType Directory -Force -Path $resolvedOutputDirectory | Out-Null

$parsedAudit = Get-Content -LiteralPath $resolvedAuditPath -Raw -Encoding UTF8 | ConvertFrom-Json
$audit = @($parsedAudit | ForEach-Object { $_ })
foreach ($entry in $audit) {
    $entry | Add-Member -NotePropertyName selected -NotePropertyValue $false -Force
}
$approved = @($audit | Where-Object { [bool]$_.accepted })
$groups = @($approved | Group-Object { [string]$_.generated.category } | Sort-Object Count -Descending)
if ($groups.Count -lt $MinCategories) {
    throw "Only $($groups.Count) approved categories are available; need $MinCategories."
}

$selected = [Collections.Generic.List[object]]::new()
$groupOffsets = @{}
$selectedQuestionKeys = @{}
$selectedEvidenceKeys = @{}
foreach ($group in $groups) { $groupOffsets[$group.Name] = 0 }

while ($selected.Count -lt $SourceCount) {
    $added = $false
    foreach ($group in $groups) {
        if ($selected.Count -ge $SourceCount) { break }
        $index = [int]$groupOffsets[$group.Name]
        if ($index -ge $group.Group.Count) { continue }
        $groupOffsets[$group.Name] = $index + 1
        $candidate = $group.Group[$index]
        $variantKeys = @($candidate.generated.variants | ForEach-Object {
            Normalize-Text ([string]$_.question)
        })
        $standardQuestion = [string](@($candidate.generated.variants |
            Where-Object { [string]$_.type -eq "standard" })[0].question)
        $positiveEvidenceKey = (Normalize-Text $standardQuestion) + "\u001f" +
                (Normalize-Text ([string]$candidate.source.answer))
        $negativeEvidenceKey = (Normalize-Text $standardQuestion) + "\u001f" +
                (Normalize-Text ([string]$candidate.generated.negativeEvidence))

        $hasDuplicate = (@($variantKeys | Select-Object -Unique)).Count -ne $variantKeys.Count
        if (-not $hasDuplicate) {
            $hasDuplicate = @($variantKeys | Where-Object { $selectedQuestionKeys.ContainsKey($_) }).Count -gt 0
        }
        if ($hasDuplicate -or $selectedEvidenceKeys.ContainsKey($positiveEvidenceKey) -or
                $selectedEvidenceKeys.ContainsKey($negativeEvidenceKey)) {
            continue
        }

        $selected.Add($candidate)
        $candidate.selected = $true
        $variantKeys | ForEach-Object { $selectedQuestionKeys[$_] = $true }
        $selectedEvidenceKeys[$positiveEvidenceKey] = $true
        $selectedEvidenceKeys[$negativeEvidenceKey] = $true
        $added = $true
    }
    if (-not $added) { break }
}

if ($selected.Count -lt $SourceCount) {
    throw "Only $($selected.Count) globally unique sources can be selected; need $SourceCount."
}

$retrievalCases = [Collections.Generic.List[object]]::new()
$evidenceCases = [Collections.Generic.List[object]]::new()
foreach ($entry in $selected) {
    $source = $entry.source
    $generated = $entry.generated
    foreach ($variant in @($generated.variants)) {
        $retrievalCases.Add([ordered]@{
            question = ([string]$variant.question).Trim()
            relevantDocIds = @([string]$source.id)
            category = [string]$generated.category
        })
    }
    $standardQuestion = [string](@($generated.variants |
        Where-Object { [string]$_.type -eq "standard" })[0].question)
    $keywords = @($generated.keywords | ForEach-Object { ([string]$_).Trim() } |
        Where-Object { $_ } | Select-Object -Unique)
    $evidenceCases.Add([ordered]@{
        question = $standardQuestion
        evidence = $source.answer
        keywords = $keywords
        expectedSupported = $true
        category = [string]$generated.category
        note = "silver positive; source qa_pair_id=$($source.id)"
    })
    $evidenceCases.Add([ordered]@{
        question = $standardQuestion
        evidence = ([string]$generated.negativeEvidence).Trim()
        keywords = $keywords
        expectedSupported = $false
        category = [string]$generated.category
        note = "silver hard negative; mutation=$($generated.mutationType); source qa_pair_id=$($source.id)"
    })
}

$retrievalPath = Join-Path $resolvedOutputDirectory "retrieval-silver.json"
$evidencePath = Join-Path $resolvedOutputDirectory "evidence-silver.json"
$outputAuditPath = Join-Path $resolvedOutputDirectory "generation-audit.json"
$manifestPath = Join-Path $resolvedOutputDirectory "manifest.json"
Write-JsonFile $retrievalPath @($retrievalCases)
Write-JsonFile $evidencePath @($evidenceCases)
Write-JsonFile $outputAuditPath @($audit)

$categoryCounts = [ordered]@{}
$selected | Group-Object { [string]$_.generated.category } | Sort-Object Name | ForEach-Object {
    $categoryCounts[$_.Name] = $_.Count
}
$manifest = if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
    Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
} else {
    [pscustomobject]@{
        schemaVersion = 1
        datasetKind = "llm-assisted-silver"
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        datasets = [pscustomobject]@{}
    }
}
$manifest | Add-Member -NotePropertyName categoryCounts -NotePropertyValue $categoryCounts -Force
$manifest.datasets | Add-Member -NotePropertyName retrieval -NotePropertyValue ([pscustomobject]@{
    file = [IO.Path]::GetFileName($retrievalPath)
    sha256 = Get-FileSha256 $retrievalPath
    caseCount = $retrievalCases.Count
}) -Force
$manifest.datasets | Add-Member -NotePropertyName evidence -NotePropertyValue ([pscustomobject]@{
    file = [IO.Path]::GetFileName($evidencePath)
    sha256 = Get-FileSha256 $evidencePath
    caseCount = $evidenceCases.Count
    hardNegativeCount = @($evidenceCases | Where-Object { -not $_.expectedSupported }).Count
    hardNegativeRatio = 0.5
}) -Force
$manifest.datasets | Add-Member -NotePropertyName audit -NotePropertyValue ([pscustomobject]@{
    file = [IO.Path]::GetFileName($outputAuditPath)
    sha256 = Get-FileSha256 $outputAuditPath
    acceptedCandidateCount = $approved.Count
    rejectedCandidateCount = @($audit | Where-Object { -not $_.accepted }).Count
    selectedCandidateCount = $selected.Count
}) -Force
Write-JsonFile $manifestPath $manifest

Write-Host "[RESULT] Selected sources: $($selected.Count) across $($categoryCounts.Count) categories"
Write-Host "[RESULT] Retrieval cases: $($retrievalCases.Count)"
Write-Host "[RESULT] Evidence cases: $($evidenceCases.Count)"
