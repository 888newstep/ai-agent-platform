[CmdletBinding()]
param(
    [string]$BaseUrl = $(if ([string]::IsNullOrWhiteSpace($env:AI_BASE_URL)) { 'http://localhost:8081' } else { $env:AI_BASE_URL }),
    [ValidateSet('health', 'search')]
    [string]$Scenario = 'health',
    [ValidateRange(1, 5000)]
    [int]$Threads = 1,
    [ValidateRange(1, 3600)]
    [int]$RampUpSeconds = 1,
    [ValidateRange(1, 86400)]
    [int]$DurationSeconds = 30,
    [ValidateRange(1, 50)]
    [int]$TopK = 5,
    [ValidateRange(0.0, 1.0)]
    [double]$Threshold = 0.7,
    [string]$Query = 'password reset',
    [ValidateRange(100, 60000)]
    [int]$ConnectTimeoutMilliseconds = 3000,
    [ValidateRange(100, 120000)]
    [int]$ResponseTimeoutMilliseconds = 10000,
    [string]$JMeterPath,
    [string]$OutputDirectory = (Join-Path (Get-Location) 'evaluation-reports'),
    [ValidateRange(1, 120)]
    [int]$PreflightTimeoutSeconds = 10,
    [switch]$SkipHealthCheck,
    [switch]$FailOnErrors
)

$ErrorActionPreference = 'Stop'

function Resolve-JMeterInvocation {
    param([string]$ConfiguredPath)

    $resolvedPath = $null
    if (-not [string]::IsNullOrWhiteSpace($ConfiguredPath)) {
        if (-not (Test-Path -LiteralPath $ConfiguredPath -PathType Leaf)) {
            throw "JMeter executable was not found: $ConfiguredPath"
        }
        $resolvedPath = (Resolve-Path -LiteralPath $ConfiguredPath).Path
    } else {
        foreach ($commandName in @('jmeter.bat', 'jmeter')) {
            $command = Get-Command $commandName -ErrorAction SilentlyContinue
            if ($null -ne $command) {
                $resolvedPath = $command.Source
                break
            }
        }
    }

    if ([string]::IsNullOrWhiteSpace($resolvedPath)) {
        throw 'JMeter was not found. Add jmeter.bat to PATH or pass -JMeterPath.'
    }

    $extension = [IO.Path]::GetExtension($resolvedPath).ToLowerInvariant()
    if ($extension -in @('.bat', '.cmd')) {
        $jmeterJar = Join-Path (Split-Path -Parent $resolvedPath) 'ApacheJMeter.jar'
        if (-not (Test-Path -LiteralPath $jmeterJar -PathType Leaf)) {
            throw "ApacheJMeter.jar was not found next to the launcher: $jmeterJar"
        }
        $javaCommand = Get-Command 'java.exe' -ErrorAction SilentlyContinue
        if ($null -eq $javaCommand) {
            throw 'java.exe was not found. JMeter requires a JDK/JRE on PATH.'
        }
        return [PSCustomObject]@{
            Command = $javaCommand.Source
            PrefixArguments = @('-jar', $jmeterJar)
        }
    }

    return [PSCustomObject]@{
        Command = $resolvedPath
        PrefixArguments = @()
    }
}

function Get-Percentile {
    param(
        [object[]]$Values,
        [double]$Percentile
    )

    $numbers = @(
        $Values |
            ForEach-Object { [double]$_ } |
            Sort-Object
    )
    if ($numbers.Count -eq 0) {
        return 0
    }

    $rank = [Math]::Ceiling(($Percentile / 100.0) * $numbers.Count)
    $index = [Math]::Max(0, [Math]::Min($numbers.Count - 1, [int]$rank - 1))
    return [Math]::Round($numbers[$index], 2)
}

function Get-Mean {
    param([object[]]$Values)

    $numbers = @($Values | ForEach-Object { [double]$_ })
    if ($numbers.Count -eq 0) {
        return 0
    }
    return [Math]::Round((($numbers | Measure-Object -Average).Average), 2)
}

try {
    $targetUri = [Uri]$BaseUrl
} catch {
    throw "BaseUrl must be an absolute http(s) URL: $BaseUrl"
}

if (-not $targetUri.IsAbsoluteUri -or $targetUri.Scheme -notin @('http', 'https')) {
    throw 'BaseUrl must be an absolute http(s) URL.'
}
if (-not [string]::IsNullOrWhiteSpace($targetUri.UserInfo) -or $targetUri.Query -or $targetUri.Fragment) {
    throw 'BaseUrl must not contain credentials, query parameters, or fragments.'
}

$protocol = $targetUri.Scheme.ToLowerInvariant()
$port = if ($targetUri.IsDefaultPort) {
    if ($protocol -eq 'https') { 443 } else { 80 }
} else {
    $targetUri.Port
}
$basePath = $targetUri.AbsolutePath.TrimEnd('/')
if ($basePath -eq '/') {
    $basePath = ''
}

$jmeterInvocation = Resolve-JMeterInvocation -ConfiguredPath $JMeterPath
$planPath = (Join-Path $PSScriptRoot '..\tests\jmeter\agent-smoke.jmx' | Resolve-Path).Path
$resolvedOutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $resolvedOutputDirectory | Out-Null

$runId = "jmeter-$Scenario-$(Get-Date -Format 'yyyyMMdd-HHmmss-fff')"
$jtlPath = Join-Path $resolvedOutputDirectory "$runId.jtl"
$logPath = Join-Path $resolvedOutputDirectory "$runId.log"
$consoleLogPath = Join-Path $resolvedOutputDirectory "$runId-console.log"
$summaryPath = Join-Path $resolvedOutputDirectory "$runId-summary.json"
$htmlPath = Join-Path $resolvedOutputDirectory "$runId-html"

$authority = $targetUri.GetLeftPart([UriPartial]::Authority).TrimEnd('/')
$healthUri = "$authority$basePath/api/v1/agent/health"

Write-Host "[INFO] Scenario: $Scenario"
Write-Host "[INFO] Target: $authority$basePath"
Write-Host "[INFO] Threads: $Threads, ramp-up: ${RampUpSeconds}s, duration: ${DurationSeconds}s"
Write-Host "[INFO] JMeter plan: $planPath"

if (-not $SkipHealthCheck) {
    Write-Host "[INFO] Health preflight: $healthUri"
    try {
        $healthResponse = Invoke-WebRequest -Uri $healthUri -Method Get -UseBasicParsing -TimeoutSec $PreflightTimeoutSeconds
        if ($healthResponse.StatusCode -lt 200 -or $healthResponse.StatusCode -ge 300) {
            throw "Health endpoint returned HTTP $($healthResponse.StatusCode)."
        }
    } catch {
        throw "Health preflight failed. Start the application or use -SkipHealthCheck. $($_.Exception.Message)"
    }
}

$thresholdText = $Threshold.ToString('0.####', [Globalization.CultureInfo]::InvariantCulture)
$jmeterArguments = @(
    '-n',
    '-t', $planPath,
    "-Jprotocol=$protocol",
    "-Jhost=$($targetUri.Host)",
    "-Jport=$port",
    "-JbasePath=$basePath",
    "-Jscenario=$Scenario",
    "-Jthreads=$Threads",
    "-JrampUpSeconds=$RampUpSeconds",
    "-JdurationSeconds=$DurationSeconds",
    "-Jquery=$Query",
    "-JtopK=$TopK",
    "-Jthreshold=$thresholdText",
    "-JconnectTimeoutMs=$ConnectTimeoutMilliseconds",
    "-JresponseTimeoutMs=$ResponseTimeoutMilliseconds",
    '-Jjmeter.save.saveservice.output_format=csv',
    '-Jjmeter.save.saveservice.timestamp_format=ms',
    '-Jjmeter.save.saveservice.response_data.on_error=false',
    '-j', $logPath,
    '-l', $jtlPath,
    '-e',
    '-o', $htmlPath
)

$stopwatch = [Diagnostics.Stopwatch]::StartNew()
Write-Host '[INFO] Starting non-GUI JMeter run...'
$allJMeterArguments = @($jmeterInvocation.PrefixArguments + $jmeterArguments)
& $jmeterInvocation.Command @allJMeterArguments 2>&1 | Tee-Object -FilePath $consoleLogPath
$jmeterExitCode = $LASTEXITCODE
$stopwatch.Stop()

if ($jmeterExitCode -ne 0) {
    throw "JMeter exited with code $jmeterExitCode. See $consoleLogPath and $logPath."
}
if (-not (Test-Path -LiteralPath $jtlPath -PathType Leaf)) {
    throw "JMeter did not produce a JTL file: $jtlPath"
}

$rows = @(Import-Csv -LiteralPath $jtlPath)
$sampleCount = $rows.Count
if ($sampleCount -eq 0) {
    throw "JMeter produced no samples. Check the scenario condition and the test plan: $planPath"
}
$successCount = @($rows | Where-Object { $_.success -eq 'true' }).Count
$errorCount = $sampleCount - $successCount
$elapsedValues = @($rows | ForEach-Object { [double]$_.elapsed })
$timestampValues = @($rows | ForEach-Object { [double]$_.timeStamp } | Sort-Object)
$sampleDurationSeconds = $null
$throughputRps = $null
if ($timestampValues.Count -gt 1) {
    $sampleDurationSeconds = [Math]::Max(0.001, ($timestampValues[-1] - $timestampValues[0]) / 1000.0)
    $throughputRps = [Math]::Round($sampleCount / $sampleDurationSeconds, 2)
}
$statusCodes = [ordered]@{}
foreach ($group in ($rows | Group-Object responseCode)) {
    $statusCodes[$group.Name] = $group.Count
}

$summary = [ordered]@{
    schemaVersion = 1
    generatedAt = (Get-Date).ToUniversalTime().ToString('o')
    scenario = $Scenario
    targetHost = $targetUri.Host
    targetPort = $port
    targetScheme = $protocol
    threads = $Threads
    rampUpSeconds = $RampUpSeconds
    durationSeconds = $DurationSeconds
    topK = if ($Scenario -eq 'search') { $TopK } else { $null }
    threshold = if ($Scenario -eq 'search') { $Threshold } else { $null }
    sampleCount = $sampleCount
    successCount = $successCount
    errorCount = $errorCount
    errorRate = if ($sampleCount -eq 0) { 0 } else { [Math]::Round($errorCount / [double]$sampleCount, 4) }
    meanElapsedMs = Get-Mean $elapsedValues
    p50ElapsedMs = Get-Percentile $elapsedValues 50
    p95ElapsedMs = Get-Percentile $elapsedValues 95
    p99ElapsedMs = Get-Percentile $elapsedValues 99
    clientProcessDurationSeconds = [Math]::Round($stopwatch.Elapsed.TotalSeconds, 2)
    sampleDurationSeconds = if ($null -eq $sampleDurationSeconds) { $null } else { [Math]::Round($sampleDurationSeconds, 3) }
    throughputRps = $throughputRps
    responseCodes = $statusCodes
    jtlFile = [IO.Path]::GetFileName($jtlPath)
    htmlReportDirectory = [IO.Path]::GetFileName($htmlPath)
}
$summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $summaryPath -Encoding utf8

Write-Host ''
Write-Host '[RESULT] JMeter run completed.'
Write-Host ("[RESULT] Samples={0}, errors={1}, errorRate={2:P2}, mean={3}ms, p95={4}ms, p99={5}ms, throughput={6} req/s" -f `
    $sampleCount, $errorCount, $summary.errorRate, $summary.meanElapsedMs, $summary.p95ElapsedMs, $summary.p99ElapsedMs, $summary.throughputRps)
Write-Host "[RESULT] Summary: $summaryPath"
Write-Host "[RESULT] JTL: $jtlPath"
Write-Host "[RESULT] HTML report: $htmlPath"

if ($FailOnErrors -and $errorCount -gt 0) {
    exit 1
}
exit 0
