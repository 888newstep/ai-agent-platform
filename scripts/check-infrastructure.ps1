[CmdletBinding()]
param(
    [string]$MysqlHost = $(if ([string]::IsNullOrWhiteSpace($env:MYSQL_HOST)) { 'localhost' } else { $env:MYSQL_HOST }),
    [int]$MysqlPort = $(if ([string]::IsNullOrWhiteSpace($env:MYSQL_PORT)) { 3306 } else { [int]$env:MYSQL_PORT }),
    [string]$RedisHost = $(if ([string]::IsNullOrWhiteSpace($env:REDIS_HOST)) { 'localhost' } else { $env:REDIS_HOST }),
    [int]$RedisPort = $(if ([string]::IsNullOrWhiteSpace($env:REDIS_PORT)) { 6379 } else { [int]$env:REDIS_PORT }),
    [string]$MilvusHost = $(if ([string]::IsNullOrWhiteSpace($env:MILVUS_HOST)) { 'localhost' } else { $env:MILVUS_HOST }),
    [int]$MilvusPort = $(if ([string]::IsNullOrWhiteSpace($env:MILVUS_PORT)) { 19530 } else { [int]$env:MILVUS_PORT }),
    [string]$MilvusDatabase = $(if ([string]::IsNullOrWhiteSpace($env:MILVUS_DATABASE_NAME)) { 'cs_agent' } else { $env:MILVUS_DATABASE_NAME }),
    [string]$MilvusCollection = $(if ([string]::IsNullOrWhiteSpace($env:MILVUS_COLLECTION_NAME)) { 'ai_agent_documents' } else { $env:MILVUS_COLLECTION_NAME }),
    [string]$RabbitHost = $env:RABBITMQ_HOST,
    [int]$RabbitPort = $(if ([string]::IsNullOrWhiteSpace($env:RABBITMQ_PORT)) { 5672 } else { [int]$env:RABBITMQ_PORT }),
    [int]$TimeoutMilliseconds = 3000,
    [switch]$SkipRabbitMq
)

$ErrorActionPreference = 'Stop'

function Assert-Port {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$TargetHost,
        [Parameter(Mandatory = $true)][int]$Port,
        [Parameter(Mandatory = $true)][int]$Timeout
    )

    if ([string]::IsNullOrWhiteSpace($TargetHost)) {
        throw "$Name host must not be blank."
    }
    if ($Port -lt 1 -or $Port -gt 65535) {
        throw "$Name port must be between 1 and 65535."
    }

    $client = New-Object System.Net.Sockets.TcpClient
    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $asyncResult = $client.BeginConnect($TargetHost, $Port, $null, $null)
        if (-not $asyncResult.AsyncWaitHandle.WaitOne($Timeout)) {
            throw "TCP connection timed out after ${Timeout}ms."
        }
        $client.EndConnect($asyncResult)
        $stopwatch.Stop()
        Write-Host ("[PASS] {0} {1}:{2} ({3} ms)" -f $Name, $TargetHost, $Port, $stopwatch.ElapsedMilliseconds)
        return $true
    } catch {
        $stopwatch.Stop()
        Write-Host ("[FAIL] {0} {1}:{2} - {3}" -f $Name, $TargetHost, $Port, $_.Exception.Message)
        return $false
    } finally {
        $client.Close()
        $client.Dispose()
    }
}

function Assert-RequiredValue {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        Write-Host "[FAIL] $Name must not be blank."
        return $false
    }
    Write-Host "[PASS] $Name = $Value"
    return $true
}

if ($TimeoutMilliseconds -lt 1) {
    throw 'TimeoutMilliseconds must be greater than zero.'
}

Write-Output 'Hybrid infrastructure preflight'
Write-Output 'Sensitive values are not read or displayed.'
Write-Output ''

$checks = @()
$checks += Assert-Port -Name 'MySQL' -TargetHost $MysqlHost -Port $MysqlPort -Timeout $TimeoutMilliseconds
$checks += Assert-Port -Name 'Redis' -TargetHost $RedisHost -Port $RedisPort -Timeout $TimeoutMilliseconds
$checks += Assert-Port -Name 'Milvus' -TargetHost $MilvusHost -Port $MilvusPort -Timeout $TimeoutMilliseconds
$checks += Assert-RequiredValue -Name 'Milvus database' -Value $MilvusDatabase
$checks += Assert-RequiredValue -Name 'Milvus collection' -Value $MilvusCollection

if (-not $SkipRabbitMq) {
    if ([string]::IsNullOrWhiteSpace($RabbitHost)) {
        Write-Output '[SKIP] RabbitMQ host is not configured; set RABBITMQ_HOST to check the external broker.'
    } else {
        $checks += Assert-Port -Name 'RabbitMQ (external, not wired into newagent)' -TargetHost $RabbitHost -Port $RabbitPort -Timeout $TimeoutMilliseconds
    }
} else {
    Write-Output '[SKIP] RabbitMQ check disabled by -SkipRabbitMq.'
}

Write-Output ''
if ($checks -contains $false) {
    Write-Output '[RESULT] FAIL - fix failed connectivity/configuration checks before running the real RAG replay.'
    exit 1
}

Write-Output '[RESULT] PASS - TCP endpoints and non-secret Milvus targets are ready for the next verification step.'
