[CmdletBinding()]
param(
    [ValidateRange(6, 200)]
    [int]$SourceCount = 30,
    [ValidateRange(1, 10)]
    [int]$CandidateMultiplier = 4,
    [ValidateRange(1, 20)]
    [int]$BatchSize = 10,
    [ValidateRange(2, 8)]
    [int]$MinCategories = 6,
    [ValidateSet("deepseek", "doubao", "qwen3-flash")]
    [string]$GeneratorProvider = "deepseek",
    [ValidateSet("deepseek", "doubao", "qwen3-flash")]
    [string[]]$JudgeProviders = @("doubao", "qwen3-flash"),
    [string]$Seed = "sp-silver-v1",
    [string]$EnvFile = ".env",
    [string]$OutputDirectory = "evaluation-datasets\silver",
    [ValidateRange(1, 10)]
    [int]$MaxRetries = 3
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$allowedCategories = @(
    "refund_return",
    "shipping_logistics",
    "product_specification",
    "promotion_price",
    "order_payment",
    "after_sales_quality",
    "usage_storage",
    "account_service"
)
$allowedMutationTypes = @(
    "numeric_conflict",
    "condition_removed",
    "negation_flip",
    "entity_swap",
    "topic_only"
)

function Import-DotEnv {
    param([string]$Path)
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $values }
    Get-Content -LiteralPath $Path -Encoding UTF8 | ForEach-Object {
        if ($_ -match "^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$") {
            $value = $Matches[2].Trim()
            if (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                    ($value.StartsWith("'") -and $value.EndsWith("'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            }
            $values[$Matches[1]] = $value
        }
    }
    return $values
}

function Get-Setting {
    param([hashtable]$DotEnv, [string]$Name, [string]$Default = "")
    $processValue = [Environment]::GetEnvironmentVariable($Name)
    if (-not [string]::IsNullOrWhiteSpace($processValue)) { return $processValue }
    if ($DotEnv.ContainsKey($Name) -and -not [string]::IsNullOrWhiteSpace([string]$DotEnv[$Name])) {
        return [string]$DotEnv[$Name]
    }
    return $Default
}

function Get-Sha256Text {
    param([string]$Value)
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha256.ComputeHash($bytes)
        return ([BitConverter]::ToString($hash) -replace "-", "").ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

function Get-FileSha256 {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-JsonFile {
    param([string]$Path, [object]$Value)
    $json = $Value | ConvertTo-Json -Depth 30
    [IO.File]::WriteAllText($Path, $json, [Text.UTF8Encoding]::new($false))
}

function Resolve-Provider {
    param([hashtable]$DotEnv, [string]$Name)
    $definition = switch ($Name) {
        "deepseek" {
            @{ keyName = "DEEPSEEK_API_KEY"; endpoint = "https://api.deepseek.com/v1/chat/completions"; model = "deepseek-chat" }
        }
        "doubao" {
            @{ keyName = "DOUBAO_API_KEY"; endpoint = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"; model = "doubao-seed-2-0-mini-260428" }
        }
        "qwen3-flash" {
            @{ keyName = "QWEN3_FLASH_API_KEY"; endpoint = "https://ws-saj0dk2icyo8g1ub.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions"; model = "qwen3.7-flash" }
        }
        default { throw "Unsupported model provider: $Name" }
    }
    $apiKey = Get-Setting $DotEnv $definition.keyName
    if ([string]::IsNullOrWhiteSpace($apiKey) -or $apiKey -match "^(your-|replace-|change-me)") {
        throw "Provider $Name requires $($definition.keyName)."
    }
    return [pscustomobject]@{
        name = $Name
        endpoint = $definition.endpoint
        model = $definition.model
        apiKey = $apiKey
    }
}

function ConvertFrom-ModelJson {
    param([string]$Content, [string]$ProviderName)
    if ([string]::IsNullOrWhiteSpace($Content)) {
        throw "Provider $ProviderName returned empty content."
    }
    $clean = $Content.Trim() -replace "(?s)^```(?:json)?\s*", "" -replace "(?s)\s*```$", ""
    $first = $clean.IndexOf('{')
    $last = $clean.LastIndexOf('}')
    if ($first -lt 0 -or $last -le $first) {
        throw "Provider $ProviderName did not return a JSON object."
    }
    try {
        return $clean.Substring($first, $last - $first + 1) | ConvertFrom-Json
    } catch {
        throw "Provider $ProviderName returned invalid JSON: $($_.Exception.Message)"
    }
}

function Invoke-ChatJson {
    param(
        [object]$Provider,
        [string]$SystemPrompt,
        [string]$UserPrompt,
        [double]$Temperature = 0.1
    )
    $body = @{
        model = $Provider.model
        messages = @(
            @{ role = "system"; content = $SystemPrompt },
            @{ role = "user"; content = $UserPrompt }
        )
        temperature = $Temperature
        max_tokens = 6000
    } | ConvertTo-Json -Depth 10
    $bodyBytes = [Text.Encoding]::UTF8.GetBytes($body)

    for ($attempt = 1; $attempt -le $MaxRetries; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri $Provider.endpoint `
                -Headers @{ Authorization = "Bearer $($Provider.apiKey)" } `
                -ContentType "application/json; charset=utf-8" -Body $bodyBytes -TimeoutSec 120
            $stream = $response.RawContentStream
            $stream.Position = 0
            $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $true)
            try {
                $responseText = $reader.ReadToEnd()
            } finally {
                $reader.Dispose()
            }
            $responseObject = $responseText | ConvertFrom-Json
            return ConvertFrom-ModelJson -Content ([string]$responseObject.choices[0].message.content) -ProviderName $Provider.name
        } catch {
            if ($attempt -eq $MaxRetries) {
                throw "Provider $($Provider.name) failed after $MaxRetries attempts: $($_.Exception.Message)"
            }
            Start-Sleep -Seconds ([Math]::Min(8, [Math]::Pow(2, $attempt)))
        }
    }
}

function Invoke-MySqlQuery {
    param(
        [string]$Executable,
        [string]$HostName,
        [int]$Port,
        [string]$Database,
        [string]$Username,
        [string]$Password,
        [string]$Query
    )
    $previousPassword = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = $Password
        $output = @(& $Executable "--default-character-set=utf8mb4" "--protocol=TCP" `
            "-h" $HostName "-P" ([string]$Port) "-u" $Username "--database=$Database" `
            "--batch" "--raw" "--skip-column-names" "-e" $Query 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $env:MYSQL_PWD = $previousPassword
    }
    if ($exitCode -ne 0) {
        throw "MySQL query failed with exit code ${exitCode}: $($output -join [Environment]::NewLine)"
    }
    return $output -join [Environment]::NewLine
}

function ConvertFrom-Base64Utf8 {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return "" }
    return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Value))
}

function Test-SensitiveText {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return $false }
    return $Text -match "(?<!\d)1[3-9]\d{9}(?!\d)|(?<!\d)\d{17}[0-9Xx](?!\d)|(?<!\d)\d{12,}(?!\d)"
}

function Test-GeneratedItem {
    param([object]$Source, [object]$Item)
    $reasons = [Collections.Generic.List[string]]::new()
    if ($null -eq $Item -or [long]$Item.sourceId -ne [long]$Source.id) { $reasons.Add("source_id_mismatch"); return @($reasons) }
    if (-not [bool]$Item.usable) { $reasons.Add("generator_rejected") }
    if ([string]$Item.category -notin $allowedCategories) { $reasons.Add("invalid_category") }
    if ([string]$Item.riskLevel -ne "low") { $reasons.Add("risk_not_low") }
    if (@($Item.riskReasons).Count -gt 0) { $reasons.Add("risk_reasons_present") }
    $keywords = @($Item.keywords | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ } | Select-Object -Unique)
    if ($keywords.Count -lt 1 -or $keywords.Count -gt 5) { $reasons.Add("invalid_keywords") }
    $variants = @($Item.variants)
    $requiredTypes = @("standard", "colloquial", "noisy", "contextual")
    if ($variants.Count -ne 4) { $reasons.Add("variant_count") }
    foreach ($type in $requiredTypes) {
        if (@($variants | Where-Object { [string]$_.type -eq $type }).Count -ne 1) { $reasons.Add("variant_type_$type") }
    }
    $normalizedQuestions = @()
    foreach ($variant in $variants) {
        $question = ([string]$variant.question).Trim()
        if ([string]::IsNullOrWhiteSpace($question) -or $question.Length -gt 200) { $reasons.Add("invalid_variant_text") }
        if (Test-SensitiveText $question) { $reasons.Add("sensitive_variant") }
        $normalizedQuestions += (($question -replace "\s+", " ").ToLowerInvariant())
    }
    if (@($normalizedQuestions | Select-Object -Unique).Count -ne $normalizedQuestions.Count) { $reasons.Add("duplicate_variants") }
    $negativeEvidence = ([string]$Item.negativeEvidence).Trim()
    if ($negativeEvidence.Length -lt 5 -or $negativeEvidence.Length -gt 800 -or $negativeEvidence -eq $Source.answer) {
        $reasons.Add("invalid_negative_evidence")
    }
    if (Test-SensitiveText $negativeEvidence) { $reasons.Add("sensitive_negative_evidence") }
    if ([string]$Item.mutationType -notin $allowedMutationTypes) { $reasons.Add("invalid_mutation_type") }
    return @($reasons | Select-Object -Unique)
}

function Get-GeneratorPrompt {
    param([object[]]$Sources)
    $sourceJson = $Sources | Select-Object id, question, answer | ConvertTo-Json -Depth 6 -Compress
    return @"
Process the following real Chinese e-commerce customer-service QA records. They are raw conversations and are not guaranteed to be complete, correct, or compliant.

Allowed category values: $($allowedCategories -join ', ')
Allowed mutationType values: $($allowedMutationTypes -join ', ')

For every record:
1. Decide whether it is suitable as a knowledge-base evaluation source. A usable answer must be complete and self-contained. Reject requests to remove negative reviews, private transfers, unsupported promises, abuse, private data, and context-dependent fragments by setting usable=false.
2. For usable=true, provide one to five Chinese keywords and create four Chinese questions that preserve the exact intent: standard, colloquial, noisy, and contextual.
3. Create one topically similar negativeEvidence that cannot support the question. Prefer a numeric conflict, removed condition, negation flip, or entity swap. Never introduce personal data.
4. riskLevel must be low, medium, or high. Use low only when the record is clearly suitable.

Return only one JSON object with this shape:
{"items":[{"sourceId":1,"usable":true,"category":"shipping_logistics","riskLevel":"low","riskReasons":[],"keywords":["keyword1","keyword2"],"variants":[{"type":"standard","question":"..."},{"type":"colloquial","question":"..."},{"type":"noisy","question":"..."},{"type":"contextual","question":"..."}],"negativeEvidence":"...","mutationType":"numeric_conflict","negativeReason":"..."}]}

Input:
$sourceJson
"@
}

function Get-JudgePrompt {
    param([object[]]$Sources, [object[]]$GeneratedItems)
    $payload = @{
        sources = @($Sources | Select-Object id, question, answer)
        generated = $GeneratedItems
    } | ConvertTo-Json -Depth 12 -Compress
    return @"
Act as an independent RAG evaluation-data auditor. Review every generated item strictly and do not defer to another model.

Rules:
- usable: the original QA is complete enough to be customer-service knowledge, is self-contained, compliant, and contains no private data.
- variantsPreserveIntent: all four Chinese questions can be answered by the original answer and do not change product, condition, amount, or timing.
- negativeUnsupported: negativeEvidence may be topically similar but cannot support the question and does not also contain the correct answer.
- categoryValid: the category is from the allowed enum and matches the intent.
- containsSensitiveData: true for phone numbers, identity numbers, order numbers, accounts, or identifiable personal data.

Return only one JSON object:
{"items":[{"sourceId":1,"usable":true,"variantsPreserveIntent":true,"negativeUnsupported":true,"categoryValid":true,"containsSensitiveData":false,"reason":"short reason"}]}

Payload:
$payload
"@
}

if ($JudgeProviders.Count -lt 2) { throw "At least two judge providers are required." }
if ($JudgeProviders -contains $GeneratorProvider) {
    throw "GeneratorProvider must not also be a judge provider."
}
if ((@($JudgeProviders | Select-Object -Unique)).Count -ne $JudgeProviders.Count) {
    throw "JudgeProviders must be unique."
}

$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$resolvedOutputDirectory = if ([IO.Path]::IsPathRooted($OutputDirectory)) {
    [IO.Path]::GetFullPath($OutputDirectory)
} else {
    [IO.Path]::GetFullPath((Join-Path $root $OutputDirectory))
}
New-Item -ItemType Directory -Force -Path $resolvedOutputDirectory | Out-Null
$retrievalPath = Join-Path $resolvedOutputDirectory "retrieval-silver.json"
$evidencePath = Join-Path $resolvedOutputDirectory "evidence-silver.json"
$auditPath = Join-Path $resolvedOutputDirectory "generation-audit.json"
$manifestPath = Join-Path $resolvedOutputDirectory "manifest.json"
$resolvedEnvFile = if ([IO.Path]::IsPathRooted($EnvFile)) { $EnvFile } else { Join-Path $root $EnvFile }
$dotEnv = Import-DotEnv $resolvedEnvFile
$generator = Resolve-Provider $dotEnv $GeneratorProvider
$judges = @($JudgeProviders | ForEach-Object { Resolve-Provider $dotEnv $_ })

$mysqlCommand = Get-Command mysql -ErrorAction Stop
$mysqlHost = Get-Setting $dotEnv "MYSQL_HOST" "localhost"
$mysqlPort = [int](Get-Setting $dotEnv "MYSQL_PORT" "3306")
$mysqlDatabase = Get-Setting $dotEnv "MYSQL_DATABASE" "ai_agent"
$mysqlUsername = Get-Setting $dotEnv "MYSQL_USERNAME" "root"
$mysqlPassword = Get-Setting $dotEnv "MYSQL_PASSWORD"
if ([string]::IsNullOrWhiteSpace($mysqlPassword)) { throw "MYSQL_PASSWORD is required." }
if ($mysqlHost -notmatch "^[A-Za-z0-9.-]+$" -or $mysqlDatabase -notmatch "^[A-Za-z0-9_]+$") {
    throw "MySQL host or database contains unsupported characters."
}

$candidateCount = $SourceCount * $CandidateMultiplier
$seedHash = (Get-Sha256Text $Seed).Substring(0, 16)
$query = @"
SELECT id,
       REPLACE(TO_BASE64(question), CHAR(10), ''),
       REPLACE(TO_BASE64(answer), CHAR(10), '')
FROM ecommerce_qa_pairs
WHERE status = 1
  AND CHAR_LENGTH(TRIM(question)) BETWEEN 6 AND 120
  AND CHAR_LENGTH(TRIM(answer)) BETWEEN 20 AND 500
ORDER BY CRC32(CONCAT(id, '$seedHash'))
LIMIT $candidateCount
"@
$rawRows = Invoke-MySqlQuery -Executable $mysqlCommand.Source -HostName $mysqlHost -Port $mysqlPort `
    -Database $mysqlDatabase -Username $mysqlUsername -Password $mysqlPassword -Query $query
$sources = [Collections.Generic.List[object]]::new()
foreach ($line in ($rawRows -split "`r?`n")) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split "`t", 3
    if ($parts.Count -ne 3) { continue }
    $source = [pscustomobject]@{
        id = [long]$parts[0]
        question = ConvertFrom-Base64Utf8 $parts[1]
        answer = ConvertFrom-Base64Utf8 $parts[2]
    }
    if (-not (Test-SensitiveText ($source.question + " " + $source.answer))) {
        $sources.Add($source)
    }
}
if ($sources.Count -lt $SourceCount) {
    throw "Only $($sources.Count) source records remained after deterministic PII filtering; need $SourceCount."
}

$audit = [Collections.Generic.List[object]]::new()
for ($offset = 0; $offset -lt $sources.Count; $offset += $BatchSize) {
    $batch = @($sources | Select-Object -Skip $offset -First $BatchSize)
    Write-Host "[GEN] Processing sources $($offset + 1)-$($offset + $batch.Count) of $($sources.Count)"
    $generatedResponse = Invoke-ChatJson -Provider $generator `
        -SystemPrompt "Build strict and auditable Chinese customer-service RAG silver-label data. Return JSON only." `
        -UserPrompt (Get-GeneratorPrompt $batch) -Temperature 0.2
    $generatedItems = @($generatedResponse.items)
    $judgeResponses = [ordered]@{}
    foreach ($judge in $judges) {
        $judgeResponses[$judge.name] = Invoke-ChatJson -Provider $judge `
            -SystemPrompt "Act as a strict and independent customer-service evaluation-data auditor. Return JSON only." `
            -UserPrompt (Get-JudgePrompt $batch $generatedItems) -Temperature 0.0
    }

    foreach ($source in $batch) {
        $generated = $generatedItems | Where-Object { [long]$_.sourceId -eq [long]$source.id } | Select-Object -First 1
        $ruleRejections = @(Test-GeneratedItem $source $generated)
        $judgeDecisions = [ordered]@{}
        foreach ($judge in $judges) {
            $decision = @($judgeResponses[$judge.name].items) |
                Where-Object { [long]$_.sourceId -eq [long]$source.id } | Select-Object -First 1
            $judgeDecisions[$judge.name] = $decision
            if ($null -eq $decision) {
                $ruleRejections += "missing_judge_$($judge.name)"
                continue
            }
            if (-not ([bool]$decision.usable)) { $ruleRejections += "judge_$($judge.name)_source" }
            if (-not ([bool]$decision.variantsPreserveIntent)) { $ruleRejections += "judge_$($judge.name)_variants" }
            if (-not ([bool]$decision.negativeUnsupported)) { $ruleRejections += "judge_$($judge.name)_negative" }
            if (-not ([bool]$decision.categoryValid)) { $ruleRejections += "judge_$($judge.name)_category" }
            if ([bool]$decision.containsSensitiveData) { $ruleRejections += "judge_$($judge.name)_sensitive" }
        }
        $audit.Add([pscustomobject]@{
            source = $source
            generated = $generated
            judges = $judgeDecisions
            accepted = @($ruleRejections | Select-Object -Unique).Count -eq 0
            selected = $false
            rejectionReasons = @($ruleRejections | Select-Object -Unique)
        })
    }
}

Write-JsonFile $auditPath @($audit)
$approved = @($audit | Where-Object { $_.accepted })
$groups = @($approved | Group-Object { [string]$_.generated.category } | Sort-Object Count -Descending)
if ($groups.Count -lt $MinCategories) {
    $available = ($groups | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join ", "
    throw "Only $($groups.Count) approved categories were produced; need $MinCategories. Available: $available"
}

$selected = [Collections.Generic.List[object]]::new()
$groupOffsets = @{}
$selectedQuestionKeys = @{}
$selectedEvidenceKeys = @{}
foreach ($group in $groups) { $groupOffsets[$group.Name] = 0 }
while ($selected.Count -lt $SourceCount) {
    $added = $false
    foreach ($group in $groups) {
        $index = [int]$groupOffsets[$group.Name]
        if ($index -lt $group.Group.Count -and $selected.Count -lt $SourceCount) {
            $groupOffsets[$group.Name] = $index + 1
            $candidate = $group.Group[$index]
            $variantKeys = @($candidate.generated.variants | ForEach-Object {
                (([string]$_.question).Trim() -replace "\s+", " ").ToLowerInvariant()
            })
            $standardQuestion = [string](@($candidate.generated.variants |
                Where-Object { $_.type -eq "standard" })[0].question)
            $positiveEvidenceKey = (($standardQuestion -replace "\s+", " ").ToLowerInvariant() + "\u001f" +
                    (([string]$candidate.source.answer -replace "\s+", " ").ToLowerInvariant()))
            $negativeEvidenceKey = (($standardQuestion -replace "\s+", " ").ToLowerInvariant() + "\u001f" +
                    (([string]$candidate.generated.negativeEvidence -replace "\s+", " ").ToLowerInvariant()))
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
    }
    if (-not $added) { break }
}
if ($selected.Count -lt $SourceCount) {
    throw "Only $($selected.Count) records passed model consensus and rule validation; need $SourceCount."
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
    $standardQuestion = [string](@($generated.variants | Where-Object { $_.type -eq "standard" })[0].question)
    $keywords = @($generated.keywords | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ } | Select-Object -Unique)
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

Write-JsonFile $retrievalPath @($retrievalCases)
Write-JsonFile $evidencePath @($evidenceCases)
Write-JsonFile $auditPath @($audit)

$gitRevision = try { (& git -C $root rev-parse HEAD 2>$null | Select-Object -First 1).Trim() } catch { "unknown" }
$categoryCounts = [ordered]@{}
@($selected | Group-Object { [string]$_.generated.category } | Sort-Object Name) | ForEach-Object {
    $categoryCounts[$_.Name] = $_.Count
}
$manifest = [ordered]@{
    schemaVersion = 1
    datasetKind = "llm-assisted-silver"
    generatedAt = (Get-Date).ToUniversalTime().ToString("o")
    seed = $Seed
    gitRevision = $gitRevision
    source = [ordered]@{
        system = "mysql"
        database = $mysqlDatabase
        table = "ecommerce_qa_pairs"
        authority = "raw_customer_service_corpus_not_approved_policy"
        candidateCount = $sources.Count
        selectedSourceCount = $selected.Count
    }
    models = [ordered]@{
        generator = [ordered]@{ provider = $generator.name; model = $generator.model }
        judges = @($judges | ForEach-Object { [ordered]@{ provider = $_.name; model = $_.model } })
        consensusRule = "all_judges_must_accept"
    }
    datasets = [ordered]@{
        retrieval = [ordered]@{
            file = [IO.Path]::GetFileName($retrievalPath)
            sha256 = Get-FileSha256 $retrievalPath
            caseCount = $retrievalCases.Count
        }
        evidence = [ordered]@{
            file = [IO.Path]::GetFileName($evidencePath)
            sha256 = Get-FileSha256 $evidencePath
            caseCount = $evidenceCases.Count
            hardNegativeCount = @($evidenceCases | Where-Object { -not $_.expectedSupported }).Count
            hardNegativeRatio = 0.5
        }
        audit = [ordered]@{
            file = [IO.Path]::GetFileName($auditPath)
            sha256 = Get-FileSha256 $auditPath
            acceptedCandidateCount = $approved.Count
            rejectedCandidateCount = @($audit | Where-Object { -not $_.accepted }).Count
        }
    }
    categoryCounts = $categoryCounts
    manualReview = [ordered]@{
        required = $true
        completed = $false
        minimumRecommendedCases = [Math]::Max(20, [Math]::Ceiling($selected.Count * 0.2))
        highRiskClaimsAllowed = $false
    }
    limitations = @(
        "The source is raw customer-service dialogue, not an approved policy corpus.",
        "LLM consensus does not establish independent human ground truth.",
        "Metrics from these datasets must be labelled as silver or synthetic evidence."
    )
}
Write-JsonFile $manifestPath $manifest

Write-Host "[RESULT] Retrieval dataset: $retrievalPath ($($retrievalCases.Count) cases)"
Write-Host "[RESULT] Evidence dataset: $evidencePath ($($evidenceCases.Count) cases, 50% hard negatives)"
Write-Host "[RESULT] Audit: $auditPath"
Write-Host "[RESULT] Manifest: $manifestPath"
Write-Host "[WARN] Manual review remains required before business-quality claims."
