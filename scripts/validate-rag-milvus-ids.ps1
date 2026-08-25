[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$DatasetPath,
    [string]$MilvusHost = $env:MILVUS_HOST,
    [ValidateRange(1, 65535)]
    [int]$MilvusPort = $(if ($env:MILVUS_PORT) { [int]$env:MILVUS_PORT } else { 19530 }),
    [string]$Database = $(if ($env:MILVUS_DATABASE_NAME) { $env:MILVUS_DATABASE_NAME } else { "cs_agent" }),
    [string]$Collection = $(if ($env:MILVUS_COLLECTION_NAME) { $env:MILVUS_COLLECTION_NAME } else { "ecommerce_qa" }),
    [string]$OutputPath,
    [switch]$Rebuild
)

$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$resolvedDataset = [IO.Path]::GetFullPath($DatasetPath)
if (-not (Test-Path -LiteralPath $resolvedDataset -PathType Leaf)) {
    throw "Dataset file not found: $resolvedDataset"
}
if ([string]::IsNullOrWhiteSpace($MilvusHost)) {
    throw "MilvusHost is required."
}
if ($MilvusHost -notmatch "^[A-Za-z0-9.-]+$" -or $Database -notmatch "^[A-Za-z0-9_]+$" -or $Collection -notmatch "^[A-Za-z0-9_]+$") {
    throw "Milvus host, database, or collection contains unsupported characters."
}

$cases = Get-Content -LiteralPath $resolvedDataset -Raw -Encoding UTF8 | ConvertFrom-Json
$ids = @($cases | ForEach-Object { @($_.relevantDocIds) } | ForEach-Object { [string]$_ } |
    Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
if ($ids.Count -eq 0) { throw "Dataset contains no relevantDocIds." }
foreach ($id in $ids) {
    $parsed = 0L
    if (-not [long]::TryParse($id, [ref]$parsed) -or $parsed -le 0) {
        throw "Milvus QA validation requires positive numeric relevantDocIds: $id"
    }
}

$pom = [xml](Get-Content (Join-Path $root "pom.xml") -Raw)
$milvusVersion = $pom.project.properties."milvus-sdk.version"
$milvusJar = Join-Path $env:USERPROFILE ".m2\repository\io\milvus\milvus-sdk-java\$milvusVersion\milvus-sdk-java-$milvusVersion.jar"
if (-not (Test-Path -LiteralPath $milvusJar -PathType Leaf)) {
    throw "Milvus SDK jar is unavailable: $milvusJar"
}

$classesDirectory = Join-Path $root "target\milvus-id-validator-classes"
New-Item -ItemType Directory -Force -Path $classesDirectory | Out-Null
$sourcePath = Join-Path $root "scripts\benchmark\MilvusQaIdValidator.java"
$javacPath = Join-Path $env:JAVA_HOME "bin\javac.exe"
$javaPath = Join-Path $env:JAVA_HOME "bin\java.exe"
& $javacPath -encoding UTF-8 -cp $milvusJar -d $classesDirectory $sourcePath
if ($LASTEXITCODE -ne 0) { throw "Milvus ID validator compilation failed with exit code $LASTEXITCODE" }

$jarPath = Join-Path $root "target\ai-agent-platform-1.0.0.jar"
if ($Rebuild -or -not (Test-Path -LiteralPath $jarPath -PathType Leaf)) {
    & mvn -o -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "Application package failed with exit code $LASTEXITCODE" }
}

$previousErrorPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $outputLines = @(& $javaPath "-Dloader.main=MilvusQaIdValidator" "-Dloader.path=$classesDirectory" `
        -cp $jarPath org.springframework.boot.loader.launch.PropertiesLauncher `
        $MilvusHost $MilvusPort $Database $Collection ($ids -join ",") 2>&1)
    $validatorExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorPreference
}
$resultLine = $outputLines | Where-Object { $_ -match "^MILVUS_ID_RESULT " } | Select-Object -Last 1
$missingLine = $outputLines | Where-Object { $_ -match "^MILVUS_MISSING_IDS=" } | Select-Object -Last 1
if ($null -eq $resultLine -or $resultLine -notmatch "requested=(\d+) found=(\d+) missing=(\d+)") {
    $diagnostic = $outputLines | Where-Object { $_ -match "Caused by:|DEADLINE_EXCEEDED|UNAVAILABLE" } |
        Select-Object -First 1
    $failureSummary = [ordered]@{
        schemaVersion = 1
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        dataset = [IO.Path]::GetFileName($resolvedDataset)
        database = $Database
        collection = $Collection
        requested = $ids.Count
        passed = $false
        error = "milvus_validation_unavailable"
        diagnostic = if ($diagnostic) { [string]$diagnostic } else { "validator exited without an ID result" }
        exitCode = $validatorExitCode
    }
    if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
        $resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resolvedOutput) | Out-Null
        [IO.File]::WriteAllText($resolvedOutput, ($failureSummary | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
    }
    $failureSummary | ConvertTo-Json -Depth 6
    exit $(if ($validatorExitCode -eq 0) { 1 } else { $validatorExitCode })
}

$summary = [ordered]@{
    schemaVersion = 1
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    dataset = [IO.Path]::GetFileName($resolvedDataset)
    database = $Database
    collection = $Collection
    requested = [int]$Matches[1]
    found = [int]$Matches[2]
    missing = [int]$Matches[3]
    missingIds = if ($missingLine) { @(($missingLine -replace "^MILVUS_MISSING_IDS=", "") -split "," | Where-Object { $_ }) } else { @() }
    passed = $validatorExitCode -eq 0
}

if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $resolvedOutput) | Out-Null
    [IO.File]::WriteAllText($resolvedOutput, ($summary | ConvertTo-Json -Depth 6), [Text.UTF8Encoding]::new($false))
}
$summary | ConvertTo-Json -Depth 6
if ($validatorExitCode -ne 0) { exit $validatorExitCode }
