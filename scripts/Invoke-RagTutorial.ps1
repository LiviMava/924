[CmdletBinding()]
param(
    [ValidateSet('all','parse','chunk','embed','retrieve','fusion','rerank','prompt','generate')] [string] $Stage = 'all',
    [ValidateSet('md','docx','pdf')] [string] $Sample = 'md',
    [ValidateSet('llm','bm25','configured')] [string] $RerankMethod = 'configured',
    [string] $Query = '去上海出差，酒店每晚最高能报销多少钱？',
    [ValidateRange(1,24)] [int] $FusionTopK = 10,
    [int] $Size = 220, [int] $Overlap = 40, [int] $TopK = 5, [double] $Threshold = 0,
    [string] $BaseUrl = 'http://127.0.0.1:8090',
    [ValidateSet('keyword','vector','hybrid')] [string] $RecallMode = 'hybrid',
    [string] $DocumentId = '',
    [ValidateRange(1,500)] [int] $FromPage = 1,
    [ValidateRange(1,500)] [int] $ToPage = 1
)
if ($DocumentId -and $Stage -ne 'all') { throw 'DocumentId requires Stage all so every step uses the selected real document.' }
. (Join-Path $PSScriptRoot 'Local-Common.ps1')
Import-LocalEnvironment
$tutorialUri = [Uri]$BaseUrl
if ($tutorialUri.Scheme -ne 'https' -and -not ($tutorialUri.Scheme -eq 'http' -and $tutorialUri.IsLoopback)) {
    throw 'Use HTTPS or a loopback HTTP endpoint.'
}
$tutorialSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$tutorialHeaders = @{}
if ($env:RAG_API_KEY) { $tutorialHeaders.Authorization = 'Bearer ' + $env:RAG_API_KEY }
$outputRoot = Get-LocalPath ('.runtime/tutorial/run-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
function Invoke-TutorialApi([string] $Path, $Body, [string] $Method = 'POST') {
    $params = @{ Uri = $BaseUrl.TrimEnd('/') + '/api/v1/tutorial' + $Path; Method = $Method;
        WebSession = $tutorialSession; Headers = $tutorialHeaders; UseBasicParsing = $true; TimeoutSec = 120 }
    if ($null -ne $Body) { $params.ContentType = 'application/json; charset=utf-8'; $params.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 20 -Compress)) }
    return (Invoke-WebRequest @params).Content | ConvertFrom-Json
}
$plan = switch ($Stage) {
    'all' { @('parse','chunk','embed','retrieve','fusion','rerank','prompt','generate') }
    'parse' { @('parse') }
    'chunk' { @('parse','chunk') }
    'embed' { @('parse','chunk','embed') }
    'retrieve' { @('parse','chunk','embed','retrieve') }
    'fusion' { @('parse','chunk','embed','retrieve','fusion') }
    'rerank' { @('rerank') }
    'prompt' { @('prompt') }
    'generate' { @('prompt','generate') }
}
if ($RecallMode -eq 'keyword' -and $Stage -in @('retrieve','fusion')) { $plan = @('parse','chunk','retrieve'); if ($Stage -eq 'fusion') { $plan += 'fusion' } }
$tutorialRun = $null
$completed = @()
$utf8 = New-Object Text.UTF8Encoding($false)
try {
    $tutorialRun = if ($DocumentId) {
        Invoke-TutorialApi '/runs/from-document' @{ documentId = $DocumentId; fromPage = $FromPage; toPage = $ToPage }
    } else { Invoke-TutorialApi '/runs' @{ sample = $Sample } }
    [IO.File]::WriteAllText((Join-Path $outputRoot '00-source.json'), ($tutorialRun | ConvertTo-Json -Depth 20), $utf8)
    foreach ($step in $plan) {
        $body = switch ($step) {
            'chunk' { @{ size = $Size; overlap = $Overlap } }
            'retrieve' { @{ query = $Query; topK = $TopK; threshold = $Threshold; mode = $RecallMode } }
            'fusion' { @{ query = $Query; topK = $FusionTopK } }
            'rerank' { @{ query = $Query; method = $RerankMethod; source = $(if ($Stage -eq 'all') { 'fused' } else { 'fixture' }) } }
            'prompt' { @{ question = $Query; topK = 3; source = $(if ($Stage -eq 'all') { 'ranked' } else { 'fixture' }) } }
            default { @{} }
        }
        $result = Invoke-TutorialApi ('/runs/' + $tutorialRun.runId + '/' + $step) $body
        [IO.File]::WriteAllText((Join-Path $outputRoot ($step + '.json')), ($result | ConvertTo-Json -Depth 40), $utf8)
        $completed += $step
        Write-Host ($step + ': ' + $result.mode + ' / ' + $result.elapsedMs + ' ms')
    }
    Write-Host ('Input/output JSON: ' + $outputRoot)
} finally {
    $cleaned = $false
    if ($null -ne $tutorialRun) {
        try { $null = Invoke-TutorialApi ('/runs/' + $tutorialRun.runId) $null 'DELETE'; $cleaned = $true }
        catch { Write-Warning 'Tutorial cleanup was not confirmed. See the run ID in 00-source.json; no automatic replay was performed.' }
    }
    [IO.File]::WriteAllText((Join-Path $outputRoot 'manifest.json'), (@{ stage = $Stage; sample = $(if ($DocumentId) { $null } else { $Sample }); documentId = $DocumentId; fromPage = $FromPage; toPage = $ToPage; completed = $completed; cleaned = $cleaned; recordedAtUtc = [DateTime]::UtcNow.ToString('o') } | ConvertTo-Json), $utf8)
}
[pscustomobject]@{ OutputDirectory = $outputRoot; Completed = $completed; Cleaned = $cleaned }
