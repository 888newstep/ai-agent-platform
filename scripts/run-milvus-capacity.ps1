[CmdletBinding()]
param(
    [string]$MilvusHost = $env:MILVUS_HOST,
    [ValidateRange(1, 65535)]
    [int]$MilvusPort = $(if ($env:MILVUS_PORT) { [int]$env:MILVUS_PORT } else { 19530 }),
    [string]$Database = $(if ($env:MILVUS_DATABASE_NAME) { $env:MILVUS_DATABASE_NAME } else { 'cs_agent' }),
    [string]$Collection = $(if ($env:MILVUS_COLLECTION_NAME) { $env:MILVUS_COLLECTION_NAME } else { 'ecommerce_qa' }),
    [ValidateRange(1, 300)]
    [int]$DurationSeconds = 10,
    [string]$ThreadLevels = '1,5,10,20,50',
    [ValidateRange(1, 200)]
    [int]$GateThreads = 50,
    [ValidateRange(0, 1000000)]
    [double]$MinGateRps = 800,
    [ValidateRange(1, 120000)]
    [double]$MaxGateP95Milliseconds = 120,
    [string]$OutputDirectory = (Join-Path (Get-Location) 'evaluation-reports\milvus-capacity'),
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($MilvusHost)) {
    throw 'MilvusHost is required. Pass -MilvusHost or set MILVUS_HOST.'
}
if ($MilvusHost -notmatch '^[A-Za-z0-9.-]+$') {
    throw 'MilvusHost contains unsupported characters.'
}
if ($Database -notmatch '^[A-Za-z0-9_]+$' -or $Collection -notmatch '^[A-Za-z0-9_]+$') {
    throw 'Database and Collection may contain only letters, digits, and underscores.'
}
$levels = @($ThreadLevels.Split(',') | ForEach-Object {
    $parsed = 0
    if (-not [int]::TryParse($_.Trim(), [ref]$parsed) -or $parsed -lt 1 -or $parsed -gt 200) {
        throw "Invalid thread level: $_"
    }
    $parsed
} | Select-Object -Unique)
if ($GateThreads -notin $levels) {
    throw 'GateThreads must be included in ThreadLevels.'
}

$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$pom = [xml](Get-Content (Join-Path $root 'pom.xml') -Raw)
$milvusVersion = $pom.project.properties.'milvus-sdk.version'
$milvusJar = Join-Path $env:USERPROFILE ".m2\repository\io\milvus\milvus-sdk-java\$milvusVersion\milvus-sdk-java-$milvusVersion.jar"
if (-not (Test-Path -LiteralPath $milvusJar -PathType Leaf)) {
    throw "Milvus SDK jar is unavailable in the local Maven repository: $milvusJar"
}

$classesDirectory = Join-Path $root 'target\milvus-benchmark-classes'
New-Item -ItemType Directory -Force -Path $classesDirectory | Out-Null
$sourcePath = Join-Path $root 'scripts\benchmark\MilvusDirectBenchmark.java'
$javacPath = Join-Path $env:JAVA_HOME 'bin\javac.exe'
$javaPath = Join-Path $env:JAVA_HOME 'bin\java.exe'
& $javacPath -encoding UTF-8 -cp $milvusJar -d $classesDirectory $sourcePath
if ($LASTEXITCODE -ne 0) {
    throw "Benchmark compilation failed with exit code $LASTEXITCODE"
}

$jarPath = Join-Path $root 'target\ai-agent-platform-1.0.0.jar'
if ($Rebuild -or -not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    & mvn -o -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw "Application package failed with exit code $LASTEXITCODE"
    }
}
$threadCsv = $levels -join ','
$outputLines = @(& $javaPath '-Dloader.main=MilvusDirectBenchmark' "-Dloader.path=$classesDirectory" `
    -cp $jarPath org.springframework.boot.loader.launch.PropertiesLauncher `
    $MilvusHost $MilvusPort $Database $Collection $DurationSeconds $threadCsv)
if ($LASTEXITCODE -ne 0) {
    $outputLines | ForEach-Object { Write-Host $_ }
    throw "Milvus benchmark failed with exit code $LASTEXITCODE"
}
$outputLines | ForEach-Object { Write-Host $_ }

$results = @()
$pattern = '^DIRECT_STAGE threads=(?<threads>\d+) success=(?<success>\d+) errors=(?<errors>\d+) rps=(?<rps>[\d.]+) meanMs=(?<mean>[\d.]+) p50Ms=(?<p50>[\d.]+) p95Ms=(?<p95>[\d.]+) p99Ms=(?<p99>[\d.]+) maxMs=(?<max>[\d.]+)$'
foreach ($line in $outputLines) {
    if ($line -match $pattern) {
        $results += [PSCustomObject]@{
            threads = [int]$Matches.threads
            success = [long]$Matches.success
            errors = [long]$Matches.errors
            rps = [double]$Matches.rps
            meanMs = [double]$Matches.mean
            p50Ms = [double]$Matches.p50
            p95Ms = [double]$Matches.p95
            p99Ms = [double]$Matches.p99
            maxMs = [double]$Matches.max
        }
    }
}
if ($results.Count -ne $levels.Count) {
    throw 'Benchmark output did not contain every requested stage.'
}

$gateResult = $results | Where-Object { $_.threads -eq $GateThreads } | Select-Object -First 1
$violations = @()
if ($gateResult.errors -gt 0) { $violations += "errors=$($gateResult.errors)" }
if ($gateResult.rps -lt $MinGateRps) { $violations += "rps=$($gateResult.rps) below $MinGateRps" }
if ($gateResult.p95Ms -gt $MaxGateP95Milliseconds) {
    $violations += "p95Ms=$($gateResult.p95Ms) above $MaxGateP95Milliseconds"
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$summaryPath = Join-Path $OutputDirectory ("milvus-capacity-{0}.json" -f (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
$summary = [ordered]@{
    schemaVersion = 1
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    host = $MilvusHost
    port = $MilvusPort
    database = $Database
    collection = $Collection
    durationSeconds = $DurationSeconds
    results = $results
    gate = [ordered]@{
        threads = $GateThreads
        minRps = $MinGateRps
        maxP95Milliseconds = $MaxGateP95Milliseconds
        passed = $violations.Count -eq 0
        violations = $violations
    }
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summaryPath -Encoding utf8
Write-Host "[RESULT] Summary: $summaryPath"
if ($violations.Count -gt 0) {
    $violations | ForEach-Object { Write-Host "[GATE] FAILED: $_" }
    exit 1
}
Write-Host '[GATE] PASSED'
