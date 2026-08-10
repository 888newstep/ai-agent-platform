[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8081",
    [string]$AdminApiKey,
    [string]$DatasetPath = "examples/evaluation-datasets/rag-sample.json",
    [string]$TopKs = "1,3,5",
    [double]$SimilarityThreshold = 0.60,
    [string]$OutputDirectory = "evaluation-reports",
    [bool]$BaselineHybridSearch = $false,
    [bool]$CandidateHybridSearch = $true
)

$ErrorActionPreference = "Stop"

if (-not [string]::IsNullOrWhiteSpace($env:AI_AGENT_BASE_URL) -and $BaseUrl -eq "http://localhost:8081") {
    $BaseUrl = $env:AI_AGENT_BASE_URL
}
if ([string]::IsNullOrWhiteSpace($AdminApiKey)) {
    $AdminApiKey = $env:ADMIN_API_KEY
}
if ([string]::IsNullOrWhiteSpace($AdminApiKey)) {
    throw "Admin API key is required. Pass -AdminApiKey or set ADMIN_API_KEY."
}
if ([string]::IsNullOrWhiteSpace($DatasetPath)) {
    throw "DatasetPath must not be blank."
}
if ($SimilarityThreshold -lt 0 -or $SimilarityThreshold -gt 1) {
    throw "SimilarityThreshold must be between 0 and 1."
}

$BaseUrl = $BaseUrl.TrimEnd('/')
$headers = @{
    "X-Admin-Api-Key" = $AdminApiKey
    "Accept" = "application/json"
}

function Build-Uri {
    param(
        [string]$Path,
        [hashtable]$Parameters
    )

    $query = ($Parameters.GetEnumerator() |
        Where-Object { $null -ne $_.Value -and -not [string]::IsNullOrWhiteSpace([string]$_.Value) } |
        ForEach-Object {
            "{0}={1}" -f [Uri]::EscapeDataString([string]$_.Key), [Uri]::EscapeDataString([string]$_.Value)
        }) -join "&"

    if ([string]::IsNullOrWhiteSpace($query)) {
        return "$BaseUrl$Path"
    }
    return "$BaseUrl$Path?$query"
}

function Invoke-JsonApi {
    param(
        [ValidateSet("GET", "POST")]
        [string]$Method,
        [string]$Uri
    )

    try {
        return Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers -TimeoutSec 120
    } catch {
        $detail = $_.ErrorDetails.Message
        if ([string]::IsNullOrWhiteSpace($detail)) {
            $detail = $_.Exception.Message
        }
        throw "API request failed ($Method $Uri): $detail"
    }
}

function Write-JsonFile {
    param(
        [string]$Path,
        [object]$Value
    )

    $json = $Value | ConvertTo-Json -Depth 30
    [System.IO.File]::WriteAllText($Path, $json, (New-Object System.Text.UTF8Encoding($false)))
}

$outputPath = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($outputPath) | Out-Null
$runId = Get-Date -Format "yyyyMMdd-HHmmss"

$health = Invoke-JsonApi -Method GET -Uri "$BaseUrl/api/v1/agent/health"
if ($health.status -ne "UP") {
    throw "Application health check failed: $($health | ConvertTo-Json -Depth 10 -Compress)"
}

$baselineUri = Build-Uri -Path "/api/v1/agent/evaluate/export" -Parameters @{
    topKs = $TopKs
    datasetPath = $DatasetPath
    similarityThreshold = $SimilarityThreshold
    hybridSearch = $BaselineHybridSearch
}
$candidateUri = Build-Uri -Path "/api/v1/agent/evaluate/export" -Parameters @{
    topKs = $TopKs
    datasetPath = $DatasetPath
    similarityThreshold = $SimilarityThreshold
    hybridSearch = $CandidateHybridSearch
}

Write-Host "Running baseline evaluation (hybridSearch=$BaselineHybridSearch)..."
$baseline = Invoke-JsonApi -Method POST -Uri $baselineUri
if (-not $baseline.exported -or [string]::IsNullOrWhiteSpace($baseline.fileName)) {
    throw "Baseline evaluation did not return an exported report."
}

Write-Host "Running candidate evaluation (hybridSearch=$CandidateHybridSearch)..."
$candidate = Invoke-JsonApi -Method POST -Uri $candidateUri
if (-not $candidate.exported -or [string]::IsNullOrWhiteSpace($candidate.fileName)) {
    throw "Candidate evaluation did not return an exported report."
}

$comparisonUri = Build-Uri -Path "/api/v1/agent/evaluate/history/compare" -Parameters @{
    baseline = $baseline.fileName
    candidate = $candidate.fileName
}
Write-Host "Comparing exported reports..."
$comparison = Invoke-JsonApi -Method GET -Uri $comparisonUri

$run = [ordered]@{
    runId = $runId
    generatedAt = [DateTime]::UtcNow.ToString("o")
    baseUrl = $BaseUrl
    datasetPath = $DatasetPath
    topKs = $TopKs
    similarityThreshold = $SimilarityThreshold
    baselineHybridSearch = $BaselineHybridSearch
    candidateHybridSearch = $CandidateHybridSearch
    baseline = $baseline
    candidate = $candidate
    comparison = $comparison
}

$runFile = Join-Path $outputPath "rag-benchmark-$runId.json"
Write-JsonFile -Path $runFile -Value $run

Write-Host "Benchmark completed."
Write-Host "Baseline report: $($baseline.fileName)"
Write-Host "Candidate report: $($candidate.fileName)"
Write-Host "Comparison comparable: $($comparison.comparable)"
Write-Host "Local run artifact: $runFile"