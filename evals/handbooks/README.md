# 100 份公开员工手册：下载、入库与问答验收

这是完整 PDF 组成的检索教学语料。2026-10-14 本机验收：**100 份、6,220 页、26,072 个正式知识片段，9/9 道问答通过**。原先的三个演示文档保留，未计入这 100 份。

来源以英文为主，包含企业、大学、地方政府及公共机构的员工/职工/学生雇员手册。数量指不同文件和正文，不代表 100 家独立公司；例如同一大学可能有普通员工与推广部门两种不同手册。少量文件为特定岗位手册或公开草稿（OU 已在标题注明），版本以下载快照为准，不代表当前有效的人事政策。

## 打开网页演示

启动项目后打开 [RAG 教学实验室](http://127.0.0.1:8090/tutorial.html)。页面上方的“百份手册，实际问答”支持：

1. 查看实际下载、就绪和片段数量，选择手册并打开原始来源。
2. 选择 HB01–HB09，检查预期来源页码，再执行真实问答。默认搜索所选机构的**完整手册**；也可切换整个知识库。
3. 选择连续 1–3 个物理页，点击“把所选页放入八步实验”，再运行整条链路，观察解析到回答的实际输入和输出。
4. 展开最近一次自动回归结果，查看每题检索、模型重排、答案事实与引用证据是否通过。

选页只是缩小教学展示范围。100 份原始 PDF 均通过正式文档 API 全文入库，不以单页或切块冒充文件数量。

## 来源与文件位置

- [sources.json](sources.json)：最终 100 个来源 URL、直接下载地址、文件名、页数、SHA-256 和抓取时间。SHA 固定本次验收版本。
- [excluded.json](excluded.json)：人工复核后排除的模板、重复版本、非手册材料及原因。
- [questions.json](questions.json)：问题、所属机构、预期事实、物理页码及断言；预期答案不会发送给模型。
- [report.json](report.json)：本次全部通过的紧凑验收快照，包含真实答案、引用位置和向量数量核验。
- [tutorial-run.json](tutorial-run.json)：真实手册选页后八步链路的实测摘要。

完整 PDF 位于 `.runtime/handbooks/files/`，逐页提取文字位于 `pages/`；完整 API 输入输出在 `evaluation-runs/`，入库状态在 `ingestion.json`。这些文件均保留在本地，仓库不再分发第三方 PDF 全文，也不提交密钥。

下载器检查 robots.txt、HTTP 状态、文件类型、20 MiB 大小限制、2–500 页、可提取文字和手册标识，再按二进制及规范化正文 SHA 去重。本次还复核了封面/开头页。遇到登录、禁止抓取、损坏、扫描无文字或固定版本发生变化会拒绝，不静默计为成功。

## 同事如何复现

应用仍只需要 Java/Maven；批量采集工具额外需要 Python 3.10+ 和 pypdf。以下命令均在项目根目录执行，依赖与下载目录留在项目内。

```powershell
python -m venv .runtime/handbook-venv
$handbookPython = Join-Path $PWD '.runtime/handbook-venv/Scripts/python.exe'
& $handbookPython -m pip install --cache-dir .runtime/pip-cache -r scripts/requirements-handbooks.txt
```

在本地 `.local.env` 填写自己的聊天和 Embedding 凭据。沿用智谱 `embedding-3`、2048 维与 DeepSeek 聊天配置；再设置：

```dotenv
RAG_RERANK_MODE=http
RAG_RERANK_URL=https://open.bigmodel.cn/api/paas/v4/rerank
RAG_RERANK_MODEL=rerank
RAG_RERANK_RAW_SCORES=true
RAG_RERANK_API_KEY=自己的智谱密钥
RAG_EMBEDDING_BATCH_SIZE=64
```

批大小 64 已在本次供应商调用中验证；其他供应商使用自己的限制，默认值仍为 16。LLM 重排会增加调用次数、延迟与费用；HTTP 专用重排仍可通过 `RAG_RERANK_MODE=http` 使用。当前默认验收使用智谱专用 `rerank`；旧 LLM 模式需显式使用 `--rerank-mode llm`。

```powershell
# 新环境先按项目 README 配置 .local.env；已有服务修改配置后需要重启应用。
.\scripts\Stop-Local.ps1 -AppOnly
.\scripts\Start-Local.ps1
. .\scripts\Local-Common.ps1
Import-LocalEnvironment
$env:PYTHONIOENCODING = 'utf-8'

# 重复执行会校验已有文件并继续；远端更新版本或撤下文件会导致非零退出。
& $handbookPython scripts/crawl_handbooks.py --target 100
if ($LASTEXITCODE) { throw '下载未达到 100 份，请检查 manifest.json。' }
& $handbookPython scripts/import_handbooks.py --minimum 100 --retry-failed
if ($LASTEXITCODE) { throw '入库未完成，请检查 ingestion.json。' }
& $handbookPython scripts/evaluate_handbooks.py --minimum 100 --publish
if ($LASTEXITCODE) { throw '问答验收未通过，请检查 evaluation-latest.json。' }
```

导入通过 `/api/v1/documents` 上传原始 PDF，由应用自己的 PDFBox、分块器、任务队列、Embedding 和 Qdrant 完成处理，不直接写业务数据库。按文件 SHA 续传，已就绪文件不会重复计算；失败文件可显式重试。大批量首次导入需要等待云端调用，默认等待上限四小时。

下载源未来可能更新或下线。固定 SHA 不匹配时应人工复核新版本、重新确认页码与事实，并更新评测集，不能直接放宽断言。`--retry-rejected` 可用于重新尝试暂时失败的下载。

评测每次写本地 `evaluation-latest.json` 和完整历史记录；**仅在所有检查通过且至少 100 份时**，`--publish` 才更新仓库内 `report.json`。网页读取最近一次本地结果，因此新一轮失败会明确显示失败，不沿用历史通过标记。

## 9 道问题如何验收

| 编号 | 机构 / 范围 | 核对事实 | 物理页 |
|---|---|---|---|
| HB01 | Carnegie Mellon | 不带薪用餐 30–60 分钟；可安排的带薪休息最多每天 15 分钟 | 12 |
| HB02 | University of Indianapolis | 普通 exempt / non-exempt 员工入职考察期 6 个月，不含警察 | 25 |
| HB03 | NEOMED | 每年 10 个带薪假日 | 17 |
| HB04 | Forty-Niner Shops | 非豁免员工每工作 4 小时享有一次 15 分钟休息 | 13 |
| HB05 | Rocky Vista University | 额外丧假可转换 3 天累计病假 | 16 |
| HB06 | Carnegie Mellon | 本校最多同时 2 门课学费 100%；符合条件的外校课程学费 50% | 26 |
| HB07 | University of Indianapolis | 中文询问英文手册中的 6 个月考察期 | 25 |
| HB08 | Carnegie Mellon | 无火星基地津贴依据，拒绝编造金额 | 不适用 |
| HB09 | Carnegie Mellon | 不能套用虚构示例中“上海酒店 650 元”的规定 | 不适用 |

每题分别调用真实 `/search` 和 `/chat`。正例要求正确文件及页码的召回片段包含事实，回答包含对应事实，**回答实际使用的引用编号**指向支持事实的原文。不能仅因 citations 数组里存在一条正确证据而算通过。负例检查拒答及禁止编造的模式，同时检查来源没有跨机构。

全部问题限定所选文档，过滤在 BM25 / 向量各自 Top-K 之前执行。源文件 SHA、正文去重、100 份 READY/HYBRID、Qdrant 按这 100 个 documentId 精确计数均是通过条件。检索与问答必须实际完成所选模型重排，发生 RRF 降级不能通过该验收。

## 八步真实手册 CLI

```powershell
. .\scripts\Local-Common.ps1
Import-LocalEnvironment
$headers = @{}
if ($env:RAG_API_KEY) { $headers.Authorization = 'Bearer ' + $env:RAG_API_KEY }
$catalog = Invoke-RestMethod http://127.0.0.1:8090/api/v1/tutorial/handbooks -Headers $headers
$document = $catalog.items | Where-Object filename -eq 'cmu-edu-b802e0ea27.pdf'
$case = $catalog.questions | Where-Object id -eq 'HB01'
.\scripts\Invoke-RagTutorial.ps1 -Stage all -DocumentId $document.documentId `
    -FromPage 12 -ToPage 12 -Size 800 -Overlap 120 -TopK 5 `
    -RerankMethod configured -Query $case.query
```

输出在 `.runtime/tutorial/run-*/`。逐步输出记录真实向量摘要、召回分数、重排顺序、完整 Prompt 和答案；结束后清理独立 collection，保留 JSON 供教学检查。

## 本次验证与边界

2026-10-14：100 份语料校验通过，Qdrant 实际点数与 26,072 个片段一致；9/9 问答、原有 6/6 全库检索冒烟及当时的 CMU 第 12 页七步实验均通过。切换智谱专用重排后，启用官方原始分数选项，9/9 真实问答再次通过。

2026-10-15：教程增加 RRF 后，Maven 61 个测试中 60 通过、1 个可选 NX 实例测试跳过；虚构手册完整八步、CMU 第 12 页真实八步及引用事实检查通过。网页资源通过语法/HTTP 检查，浏览器点击与视觉验收未完成，此前浏览器工具的 URL 策略阻止访问本地地址。最新 [tutorial-run.json](tutorial-run.json)包含融合阶段与智谱实际重排分数。

9 道题覆盖 5 个机构，是固定快照上的有限回归；不代表 100 个机构所有问题的准确率。原有 6 道全库测试仅检查来源命中和关键词。当前不能保证自动辨识任意机构、所有跨语言问题或复杂跨文档推理，也未加入 OCR。`documentId` 是范围筛选，企业用户/组织 ACL 仍需另外实现。

本次修复了一个实测问题：原 RRF 排名会漏掉 Forty-Niner Shops 的具体休息时长段落。增加 LLM Top-K 重排后，检索和回答都能引用第 13 页的正确证据；严格检查模型返回序号的数量、唯一性与范围，异常会明确降级。事实断言接受等价中英文数值表达，不要求模型逐字复述。
