[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8081",
    [string]$AdminApiKey,
    [string]$DatasetPath = "examples/evaluation-datasets/rag-sample.json",
    [ValidateSet("unknown", "sample", "smoke", "llm-assisted-silver", "synthetic", "independent-human-labeled")]
    [string]$DatasetKind = "unknown",
    [string]$TopKs = "1,3,5",
    [double]$SimilarityThreshold = 0.60,
    [string]$OutputDirectory = "evaluation-reports",
    [bool]$BaselineHybridSearch = $false,
    [bool]$CandidateHybridSearch = $true,
    [string]$VectorStoreMode,
    [string]$MilvusCollection,
    [string]$MilvusReadOnly
)

$ErrorActionPreference = "Stop"
$repositoryRoot = Split-Path -Parent $PSScriptRoot

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
    return "${BaseUrl}${Path}?${query}"
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

function Get-GitCommit {
    try {
        $commit = (& git -C $repositoryRoot rev-parse HEAD 2>$null | Select-Object -First 1)
        if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($commit)) {
            return $commit.Trim()
        }
    } catch {
        Write-Warning "Unable to resolve the current Git commit: $($_.Exception.Message)"
    }
    return "unknown"
}

function Get-DatasetProvenance {
    param(
        [string]$Path
    )

    $provenance = [ordered]@{
        fileName = [System.IO.Path]::GetFileName($Path)
        sha256 = $null
        sizeBytes = $null
    }

    try {
        $localPath = [System.IO.Path]::GetFullPath($Path)
        if (Test-Path -LiteralPath $localPath -PathType Leaf) {
            $file = Get-Item -LiteralPath $localPath
            $provenance.sha256 = (Get-FileHash -LiteralPath $localPath -Algorithm SHA256).Hash.ToLowerInvariant()
            $provenance.sizeBytes = $file.Length
        } else {
            Write-Warning "Dataset file is not available locally; provenance hash will be omitted: $Path"
        }
    } catch {
        Write-Warning "Unable to calculate dataset provenance: $($_.Exception.Message)"
    }

    return $provenance
}

function Get-SafeReport {
    param(
        [object]$Report
    )

    if ($null -eq $Report) {
        return $null
    }

    $safeReport = [ordered]@{}
    foreach ($property in ($Report | Get-Member -MemberType NoteProperty)) {
        $value = $Report.($property.Name)
        switch ($property.Name) {
            "datasetSource" {
                $safeReport[$property.Name] = [System.IO.Path]::GetFileName([string]$value)
            }
            "summary" {
                $safeReport[$property.Name] = ([string]$value) -replace '(?i)dataset=.*?, size=', 'dataset=<private>, size='
            }
            default {
                $safeReport[$property.Name] = $value
            }
        }
    }
    return $safeReport
}

function Get-SafeExport {
    param(
        [object]$Export
    )

    return [ordered]@{
        exported = $Export.exported
        fileName = $Export.fileName
        generatedAt = $Export.generatedAt
        report = Get-SafeReport -Report $Export.report
    }
}

function Get-SafeComparison {
    param(
        [object]$Comparison
    )

    $safeComparison = [ordered]@{}
    foreach ($property in ($Comparison | Get-Member -MemberType NoteProperty)) {
        if ($property.Name -in @("baseline", "candidate")) {
            $safeComparison[$property.Name] = Get-SafeReport -Report $Comparison.($property.Name)
        } else {
            $safeComparison[$property.Name] = $Comparison.($property.Name)
        }
    }
    return $safeComparison
}

$outputPath = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($outputPath) | Out-Null
$runId = Get-Date -Format "yyyyMMdd-HHmmss"
$datasetProvenance = Get-DatasetProvenance -Path $DatasetPath
$runtimeVectorStoreMode = if ([string]::IsNullOrWhiteSpace($VectorStoreMode)) { $env:AI_VECTOR_STORE_MODE } else { $VectorStoreMode }
$runtimeMilvusCollection = if ([string]::IsNullOrWhiteSpace($MilvusCollection)) { $env:MILVUS_COLLECTION_NAME } else { $MilvusCollection }
$runtimeMilvusReadOnly = if ([string]::IsNullOrWhiteSpace($MilvusReadOnly)) { $env:MILVUS_READ_ONLY } else { $MilvusReadOnly }
$runtimeProvenance = [ordered]@{
    vectorStoreMode = if ([string]::IsNullOrWhiteSpace($runtimeVectorStoreMode)) { "unknown" } else { $runtimeVectorStoreMode.Trim() }
    milvusCollection = if ([string]::IsNullOrWhiteSpace($runtimeMilvusCollection)) { "unknown" } else { $runtimeMilvusCollection.Trim() }
    milvusReadOnly = if ([string]::IsNullOrWhiteSpace($runtimeMilvusReadOnly)) { "unknown" } else { $runtimeMilvusReadOnly.Trim() }
}

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
    gitCommit = Get-GitCommit
    dataset = $datasetProvenance
    datasetKind = $DatasetKind
    runtime = $runtimeProvenance
    topKs = $TopKs
    similarityThreshold = $SimilarityThreshold
    baselineHybridSearch = $BaselineHybridSearch
    candidateHybridSearch = $CandidateHybridSearch
    baseline = Get-SafeExport -Export $baseline
    candidate = Get-SafeExport -Export $candidate
    comparison = Get-SafeComparison -Comparison $comparison
}

$runFile = Join-Path $outputPath "rag-benchmark-$runId.json"
Write-JsonFile -Path $runFile -Value $run

Write-Host "Benchmark completed."
Write-Host "Baseline report: $($baseline.fileName)"
Write-Host "Candidate report: $($candidate.fileName)"
Write-Host "Comparison comparable: $($comparison.comparable)"
Write-Host "Local run artifact: $runFile"
