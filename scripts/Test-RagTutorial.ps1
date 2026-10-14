[CmdletBinding()]
param([switch] $Offline)
$ErrorActionPreference = 'Stop'
$runner = Join-Path $PSScriptRoot 'Invoke-RagTutorial.ps1'
function Assert-Demo([bool] $Condition, [string] $Message) { if (-not $Condition) { throw $Message } }
function Read-Demo($Run, [string] $Stage) { Get-Content -LiteralPath (Join-Path $Run.OutputDirectory ($Stage + '.json')) -Raw | ConvertFrom-Json }
$word = & $runner -Stage parse -Sample docx
$pdf = & $runner -Stage parse -Sample pdf
$chunk = & $runner -Stage chunk -Size 220 -Overlap 40
$wide = & $runner -Stage chunk -Size 800 -Overlap 120
$rank = & $runner -Stage rerank -RerankMethod bm25
$prompt = & $runner -Stage prompt
Assert-Demo ($word.Cleaned -and $pdf.Cleaned -and $chunk.Cleaned -and $wide.Cleaned -and $rank.Cleaned -and $prompt.Cleaned) 'Offline demo cleanup failed'
$wordData = Read-Demo $word 'parse'
Assert-Demo (@($wordData.output.sections | Where-Object { $_.text -match '上海\s+650' }).Count -gt 0) 'Word table content missing'
Assert-Demo (@($wordData.output.sections | Where-Object { $null -ne $_.page }).Count -eq 0) 'Word must not invent page numbers'
$pdfData = Read-Demo $pdf 'parse'
Assert-Demo (($pdfData.output.sections.page -join ',') -eq '1,2') 'PDF physical page numbers are incorrect'
Assert-Demo ((Read-Demo $chunk 'chunk').output.chunkCount -gt (Read-Demo $wide 'chunk').output.chunkCount) 'Chunk parameter experiment did not change granularity'
Assert-Demo ((Read-Demo $prompt 'prompt').output.messages[0].role -eq 'system') 'Prompt system role missing'
Assert-Demo ((Read-Demo $rank 'rerank').mode -eq 'LOCAL_BM25_BASELINE') 'Offline reranker was mislabeled'
if (-not $Offline) {
    $full = & $runner -Stage all
    Assert-Demo ($full.Completed.Count -eq 8 -and $full.Cleaned) 'End-to-end pipeline or cleanup incomplete'
    $embedded = Read-Demo $full 'embed'
    $recalled = Read-Demo $full 'retrieve'
    $fused = Read-Demo $full 'fusion'
    Assert-Demo ($fused.mode -eq 'LOCAL_RRF' -and $fused.output.selectedCount -gt 0) 'RRF stage missing'
    foreach ($row in $fused.output.contributions) { Assert-Demo ([math]::Abs($row.fusedScore - $row.keywordContribution - $row.vectorContribution) -lt 1e-10) 'RRF contribution mismatch' }
    $reranked = Read-Demo $full 'rerank'
    Assert-Demo (@($reranked.output.comparison | Where-Object { $null -eq $_.rerankScore }).Count -eq 0) 'Configured reranker did not provide scores'
    $generated = Read-Demo $full 'generate'
    Assert-Demo ($embedded.output.database.dimensions -gt 0 -and $embedded.output.database.pointsCount -eq (Read-Demo $full 'chunk').output.chunkCount) 'Real Qdrant point count or dimensions are inconsistent'
    foreach ($check in $recalled.output.cosineChecks) { Assert-Demo ([math]::Abs($check.qdrantScore - $check.recomputedCosine) -lt 0.00001) 'Qdrant score differs from recomputed Cosine' }
    Assert-Demo ($reranked.mode -eq 'LIVE_CONFIGURED_RERANK') 'The full demo must use configured model reranking'
    Assert-Demo ($generated.output.citationNumbersValid -and $generated.output.answer -match '650') 'Factual/citation smoke check failed; inspect the model output'
    $strict = & $runner -Stage retrieve -RecallMode vector -Threshold 0.99
    Assert-Demo ((Read-Demo $strict 'retrieve').output.hitCount -eq 0 -and $strict.Cleaned) 'High-threshold negative case failed'
}
Write-Host 'RAG tutorial smoke checks passed. Model quality checks cover fictional fixtures only.'
