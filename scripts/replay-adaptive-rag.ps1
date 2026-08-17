[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8081",
    [string]$AdminApiKey,
    [string]$QueryPath = "examples/evaluation-datasets/rag-sample.json",
    [string]$OutputDirectory = "evaluation-reports",
    [int]$MaxQueries = 0,
    [int]$DelayMilliseconds = 0,
    [switch]$NoRag,
    [switch]$IncludeQuestions,
    [switch]$FailFast
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
if ($MaxQueries -lt 0) {
    throw "MaxQueries must be zero or a positive integer."
}
if ($DelayMilliseconds -lt 0) {
    throw "DelayMilliseconds must be zero or a positive integer."
}

$BaseUrl = $BaseUrl.TrimEnd('/')
$headers = @{
    "X-Admin-Api-Key" = $AdminApiKey
    "Accept" = "application/json"
}

function Resolve-RepositoryPath {
    param([string]$Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $Path))
}

function Write-JsonFile {
    param(
        [string]$Path,
        [object]$Value
    )

    $json = $Value | ConvertTo-Json -Depth 30
    [System.IO.File]::WriteAllText($Path, $json, (New-Object System.Text.UTF8Encoding($false)))
}

function Get-Sha256 {
    param([string]$Text)

    $sha256 = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        return ([System.BitConverter]::ToString($sha256.ComputeHash($bytes))).Replace("-", "").ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

function Get-FileProvenance {
    param([string]$Path)

    $file = Get-Item -LiteralPath $Path -ErrorAction Stop
    return [ordered]@{
        fileName = $file.Name
        sha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
        sizeBytes = $file.Length
    }
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

function Get-QueryItems {
    param([string]$Path)

    $parsed = Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($null -ne $parsed.queries) {
        return $parsed.queries | ForEach-Object { $_ }
    }
    return $parsed | ForEach-Object { $_ }
}

function Get-QueryValue {
    param(
        [object]$Item,
        [int]$Index
    )

    if ($Item -is [string]) {
        $question = $Item.Trim()
        $category = $null
    } else {
        $question = [string]$Item.question
        $category = if ($Item.PSObject.Properties.Name -contains "category") {
            [string]$Item.category
        } else {
            $null
        }
    }

    if ([string]::IsNullOrWhiteSpace($question)) {
        throw "Query item at index $Index must contain a non-empty question."
    }

    return [pscustomobject]@{
        question = $question
        category = $category
    }
}

function Build-Uri {
    param(
        [string]$Path,
        [hashtable]$Parameters
    )

    $query = ($Parameters.GetEnumerator() |
        Where-Object { $null -ne $_.Value } |
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
        [string]$Path,
        [hashtable]$Parameters = @{}
    )

    $uri = Build-Uri -Path $Path -Parameters $Parameters
    try {
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -TimeoutSec 180
    } catch {
        $detail = $_.ErrorDetails.Message
        if ([string]::IsNullOrWhiteSpace($detail)) {
            $detail = $_.Exception.Message
        }
        throw "API request failed ($Method $Path): $detail"
    }
}

function Get-Percentile {
    param(
        [double[]]$Values,
        [double]$Probability
    )

    if ($null -eq $Values -or $Values.Count -eq 0) {
        return $null
    }

    $sorted = @($Values | Sort-Object)
    $position = $Probability * ($sorted.Count - 1)
    $lower = [int][Math]::Floor($position)
    $upper = [int][Math]::Ceiling($position)
    if ($lower -eq $upper) {
        return [Math]::Round([double]$sorted[$lower], 2)
    }

    $weight = $position - $lower
    $value = ([double]$sorted[$lower] * (1 - $weight)) + ([double]$sorted[$upper] * $weight)
    return [Math]::Round($value, 2)
}

function Get-NumericSummary {
    param([double[]]$Values)

    if ($null -eq $Values -or $Values.Count -eq 0) {
        return [ordered]@{ count = 0; average = $null; p50 = $null; p95 = $null; p99 = $null }
    }

    return [ordered]@{
        count = $Values.Count
        average = [Math]::Round((($Values | Measure-Object -Average).Average), 2)
        p50 = Get-Percentile -Values $Values -Probability 0.50
        p95 = Get-Percentile -Values $Values -Probability 0.95
        p99 = Get-Percentile -Values $Values -Probability 0.99
    }
}

function Add-Count {
    param(
        [hashtable]$Counts,
        [string]$Key
    )

    if ([string]::IsNullOrWhiteSpace($Key)) {
        $Key = "unknown"
    }
    if (-not $Counts.ContainsKey($Key)) {
        $Counts[$Key] = 0
    }
    $Counts[$Key]++
}

function Convert-ContextToRecord {
    param(
        [object]$Context,
        [object]$Query,
        [int]$Index,
        [long]$LatencyMs,
        [switch]$IncludeQuestion
    )

    $rounds = @($Context.roundTraces) | ForEach-Object {
        $chunks = @($_.retrievedChunks) | ForEach-Object {
            [ordered]@{
                chunkId = $_.chunkId
                score = $_.score
                retrievalSource = $_.retrievalSource
                vectorRank = $_.vectorRank
                bm25Rank = $_.bm25Rank
                rrfScore = $_.rrfScore
            }
        }

        [ordered]@{
            round = $_.round
            rewrittenQuery = if ($IncludeQuestion) { $_.rewrittenQuery } else { $null }
            chunkCount = $_.chunkCount
            retrievedChunks = @($chunks)
            verificationLevel = $_.verificationLevel
            verificationScore = $_.verificationScore
            matchedKeywords = @($_.matchedKeywords)
            missingKeywords = @($_.missingKeywords)
            terminal = $_.terminal
            terminalReason = $_.terminalReason
        }
    }

    $retrievalRounds = [int]$Context.retrievalRounds
    $chunkCount = [int]$Context.chunkCount
    $evidenceStatus = if ($retrievalRounds -eq 0) { "not_requested" } elseif ($chunkCount -gt 0) { "available" } else { "empty" }
    $record = [ordered]@{
        index = $Index
        status = "ok"
        querySha256 = Get-Sha256 -Text $Query.question
        category = $Query.category
        question = if ($IncludeQuestion) { $Query.question } else { $null }
        routeType = [string]$Context.routeType
        decisionConfidence = [double]$Context.decisionConfidence
        decisionReason = $Context.decisionReason
        rewrittenQuery = if ($IncludeQuestion) { $Context.rewrittenQuery } else { $null }
        verificationLevel = [string]$Context.verificationLevel
        verificationReason = $Context.verificationReason
        retrievalRounds = $retrievalRounds
        chunkCount = $chunkCount
        evidenceStatus = $evidenceStatus
        rewritten = [bool]$Context.rewritten
        verified = [bool]$Context.verified
        usedAdaptive = [bool]$Context.usedAdaptive
        endReason = $Context.endReason
        clientLatencyMs = $LatencyMs
        roundTraces = @($rounds)
    }
    return $record
}

$queryFile = Resolve-RepositoryPath -Path $QueryPath
if (-not (Test-Path -LiteralPath $queryFile -PathType Leaf)) {
    throw "Query file does not exist: $QueryPath"
}

$items = @(Get-QueryItems -Path $queryFile)
if ($items.Count -eq 0) {
    throw "Query file contains no query items: $QueryPath"
}
if ($MaxQueries -gt 0) {
    $items = @($items | Select-Object -First $MaxQueries)
}

$outputPath = Resolve-RepositoryPath -Path $OutputDirectory
[System.IO.Directory]::CreateDirectory($outputPath) | Out-Null

$health = Invoke-JsonApi -Method GET -Path "/api/v1/agent/health"
if ($health.status -ne "UP") {
    throw "Application health check failed: $($health | ConvertTo-Json -Depth 10 -Compress)"
}

$records = [System.Collections.Generic.List[object]]::new()
for ($index = 0; $index -lt $items.Count; $index++) {
    $query = Get-QueryValue -Item $items[$index] -Index $index
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $context = Invoke-JsonApi -Method POST -Path "/api/v1/agent/rag/debug" -Parameters @{
            question = $query.question
            useRag = (-not $NoRag).ToString().ToLowerInvariant()
        }
        $stopwatch.Stop()
        $records.Add((Convert-ContextToRecord -Context $context -Query $query -Index $index -LatencyMs $stopwatch.ElapsedMilliseconds -IncludeQuestion:$IncludeQuestions))
        $queryHash = (Get-Sha256 -Text $query.question).Substring(0, 12)
        Write-Host ("[{0}/{1}] query={2} route={3} verification={4} rounds={5}" -f ($index + 1), $items.Count, $queryHash, $context.routeType, $context.verificationLevel, $context.retrievalRounds)
    } catch {
        $stopwatch.Stop()
        $failure = [ordered]@{
            index = $index
            status = "failed"
            querySha256 = Get-Sha256 -Text $query.question
            category = $query.category
            question = if ($IncludeQuestions) { $query.question } else { $null }
            clientLatencyMs = $stopwatch.ElapsedMilliseconds
            error = $_.Exception.Message
        }
        $records.Add($failure)
        Write-Warning ("[{0}/{1}] query failed: {2}" -f ($index + 1), $items.Count, $_.Exception.Message)
        if ($FailFast) {
            throw
        }
    }

    if ($DelayMilliseconds -gt 0 -and $index -lt ($items.Count - 1)) {
        Start-Sleep -Milliseconds $DelayMilliseconds
    }
}

$successful = @($records | Where-Object { $_.status -eq "ok" })
$failed = @($records | Where-Object { $_.status -ne "ok" })
$routeCounts = @{}
$evidenceStatusCounts = @{}
$endReasonCounts = @{}
$verificationCounts = @{}
foreach ($record in $successful) {
    Add-Count -Counts $routeCounts -Key $record.routeType
    Add-Count -Counts $endReasonCounts -Key $record.endReason
    Add-Count -Counts $verificationCounts -Key $record.verificationLevel
    Add-Count -Counts $evidenceStatusCounts -Key $record.evidenceStatus
}

$evidenceReadyRecords = @($successful | Where-Object { $_.evidenceStatus -eq "available" })
$emptyEvidenceRecords = @($successful | Where-Object { $_.evidenceStatus -eq "empty" })
$benchmarkReady = $NoRag -or $evidenceReadyRecords.Count -gt 0
$benchmarkWarning = if ($NoRag) { "RAG disabled; this replay does not validate retrieval evidence." } elseif (-not $benchmarkReady) { "No retrieval evidence was returned; do not treat this replay as a valid RAG baseline." } else { $null }

$report = [ordered]@{
    generatedAt = [DateTime]::UtcNow.ToString("o")
    gitCommit = Get-GitCommit
    querySource = Get-FileProvenance -Path $queryFile
    baseUrl = $BaseUrl
    useRag = (-not $NoRag)
    includeQuestions = [bool]$IncludeQuestions
    queryCount = $records.Count
    successCount = $successful.Count
    failureCount = $failed.Count
    evidenceReadyCount = $evidenceReadyRecords.Count
    emptyEvidenceCount = $emptyEvidenceRecords.Count
    benchmarkReady = $benchmarkReady
    benchmarkWarning = $benchmarkWarning
    distributions = [ordered]@{
        routeType = $routeCounts
        endReason = $endReasonCounts
        verificationLevel = $verificationCounts
        evidenceStatus = $evidenceStatusCounts
    }
    latencyMs = Get-NumericSummary -Values @($records | ForEach-Object { [double]$_.clientLatencyMs })
    decisionConfidence = Get-NumericSummary -Values @($successful | ForEach-Object { [double]$_.decisionConfidence })
    retrievalRounds = Get-NumericSummary -Values @($successful | ForEach-Object { [double]$_.retrievalRounds })
    chunkCount = Get-NumericSummary -Values @($successful | ForEach-Object { [double]$_.chunkCount })
    decisions = @($records)
}

$runId = Get-Date -Format "yyyyMMdd-HHmmss"
$reportFile = Join-Path $outputPath "adaptive-rag-replay-$runId.json"
Write-JsonFile -Path $reportFile -Value $report

Write-Host "Replay report: $reportFile"
Write-Host ("Completed: {0}/{1} successful, {2} failed" -f $successful.Count, $records.Count, $failed.Count)
