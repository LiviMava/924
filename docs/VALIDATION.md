# 验证记录

## 2026-10-13：教程最终验收

- 修复教学页面数值输入仅在 change 时同步的问题，同时监听 input；运行期间拒绝重复启动前端操作，同一个文件可再次上传。JavaScript 语法检查与重新打包通过；Java 后端未再改动，沿用上一节完整测试报告。
- 恢复本地服务后再次运行 `Test-RagTutorial.ps1`，真实 Embedding、Qdrant、LLM 重排、回答与高阈值对照全部通过。
- 真实 API 验证 topK=2 的 Prompt 确实只选择两条证据，完整状态可导出；跨会话读取返回 404，越界分块参数返回 400。测试实验全部清理，剩余教学 collection 为 0，应用健康状态 UP。
- 今天的浏览器工具拒绝访问 localhost，未将接口验收表述为新增网页交互验收；保留 10 月 12 日完成的网页完整流程截图。页面可由用户直接打开 `/tutorial.html`，此前页面流程及离线选择项已有实测记录。

## 2026-10-12：RAG 教程和七个逐步 demo

- 增加 `/tutorial.html` 教学实验室、七节讲义、七个独立命令行入口和完整流程/冒烟验收脚本。每步返回原理备注、实际输入、实际输出、模式与耗时。
- Maven package 成功：50 项测试，0 失败，0 错误，1 项未启用的真实 NX 只读测试跳过。新增测试覆盖教学 API 鉴权和跨站写入、参数约束、会话归属、解析来源、流程前置条件、维度不匹配、索引隔离、结果失效、重排排列校验与 Prompt 数据边界。真实 Qdrant 集成测试启用。
- 所有独立入口实际运行，包括 Word/PDF、800/120 分块、真实 Embedding 入库、高阈值召回、真实 LLM 重排、离线 BM25、独立 Prompt 和真实回答。CLI 实验结束后全部确认清理。
- 真实完整链路：791 字符 Markdown → 6 个片段 → 智谱 embedding-3 / 2048 维 → Qdrant 6 点 → 召回 5 条。Qdrant 第一条分数 0.5751498，本地 Cosine 为 0.5751497241，误差小于 0.00001；0.99 阈值返回零条。
- DeepSeek 实际重排将原第 4 条住宿依据排到第一；最终回答包含上海酒店每人每晚最高 650 元及有效引用。独立候选集亦将 fixture-2 的上海住宿依据排第一。LLM 排序不伪造相关性分数，不宣称专用 Cross-Encoder 已部署。
- 浏览器完整流程已验证，页面展示真实回答和来源；离线重排选择项实际执行并显示排序前后。Word 与 PDF 还通过实际 multipart 上传、解析及清理验证，分别输出 7 个章节和 2 个物理页。
- 实测快照在 `tutorial/outputs/`，浏览器截图在忽略目录 `.runtime/tutorial-complete.png`。可运行 `scripts/Test-RagTutorial.ps1` 重现真实链路，`-Offline` 只运行本地对照。
- 教学 API 默认关闭，Start-Local 仅对本地启动启用。教学 collection 使用独立前缀，不写公司文档表、不操作 NX。专用重排服务模式需要另行配置；生产 PostgreSQL、OCR、企业评测仍不在本次验收范围。

## 2026-10-12：智谱 Embedding 与真实 NX 写入验收

- 用户提供的智谱密钥仅保存到忽略文件 `.local.env`。已通过官方 HTTP 接口和本项目实际 Spring AI SDK 验证 `embedding-3`，实际输出 2048 维。
- 现有三份演示文档全部重建为 READY / HYBRID。Qdrant 当前 collection 为 2048 维、3 个 points；中文 BM25 与向量召回通过 RRF 融合，检索冒烟集 6/6 通过，返回模式为 HYBRID。
- 通过本服务实际调用 DeepSeek 问答，正确返回住宿标准“每晚 650 元”和有效引用，grounded=true、retrievalMode=HYBRID。
- 旧待确认计划因超过五分钟而被拒绝，未修改 NX；随后按原参数重新预览，在真实 NX 2512 的 model1 毫米零件执行成功。
- 在 NX 原生块特征界面核对长度 100、宽度 50、高度 20 mm，并验证撤销。发现旧桥接在当前会话不能重做，修复为在可见 UndoMark 前显式 EnableRedo(true)。
- 桥接新增固定绝对 X/Y 方向、创建后检查一个独立实体与实际包围盒；修复版 `NxBridge.Tested.dll` 已实际加载并执行，回执返回 geometryVerified=true、actualBoundsMm=[0,0,0,100,50,20]、actualSizeMm=[100,50,20]。
- 修复版真实 Undo 后实体消失，Redo 后同一实体恢复；MCP 读取确认最终特征数 1、实体数 1。测试结束保留该实体，没有保存或覆盖零件文件。
- 重复读取同一命令回执得到相同 SUCCEEDED 与几何核验结果，模型 snapshot 和实体数保持不变；记录见 `.runtime/nx-receipt-verified.json`。网页最终状态为“执行成功”，截图见 `.runtime/nx-chat-completed.jpg`。
- 实际回执、NX 原生尺寸检查、撤销和最终模型的证据保存在 `.runtime/nx-execution-validated.json`、`.runtime/nx-dimensions-verified.png`、`.runtime/nx-undo-verified.png`、`.runtime/nx-model-verified.png`。

以下为此前阶段的历史记录；Embedding 未配置和 NX 写入未验收的旧状态已由本次记录更新。

## 2026-10-12：MCP / NX 扩展

- RAG 模块 40 项测试通过，MCP Server 模块 4 项测试通过。RAG 测试包含真实 Qdrant 和显式启用的真实 NX 只读/预览测试；MCP 写入测试使用明确标注的 MOCK 后端。
- NX 桥接 DLL 针对本机 NX 2512 managed 程序集编译成功；用户在 NX 内加载并启用后，官方 MCP Java Client 成功完成初始化、工具发现、读取毫米工作零件和长方体预览。预览前后 snapshot 相同。
- 实际 DeepSeek + RAG Host + MCP Server + NX Open 链路成功生成建模计划，状态 PENDING；浏览器确认卡片显示目标零件、尺寸和原点。
- 页面使用真实 `model1` 毫米零件生成 100 × 50 × 20 mm、绝对原点 (0,0,0) 的计划，并展示 NX 接入演示文档作为检索来源。确认按钮、取消按钮及收起的接口详情已在实际浏览器检查；截图保存于 `.runtime/nx-chat-preview.jpg`。原检索冒烟集仍为 6/6 通过。
- 新增覆盖：会话绑定、参数 hash 校验、取消、过期、并发确认只领取一次、重复确认去重、超时 UNKNOWN、回执查询不重放、重启恢复、模型请求写入被拒绝、原子文件队列与无桥接时失败。
- 模型 SDK HTTP 测试验证工具选项保留配置模型，且直接 ChatModel 不隐式执行 ToolCallback；真实 DeepSeek 工具调用已验证。
- 真实 NX 写入、几何尺寸核验和 Undo 尚未完成验收；需用户在可丢弃零件上确认具体计划。没有以模拟写入或编译通过替代真实 CAD 写入验证。
- [接入指南与生产差距](MCP_NX.md) 包含部署、API、工具合同、恢复和按优先级排列的改进建议。

验证环境：Windows、JDK 17、本机 Qdrant 1.19.1、H2、2026-09-24。

## 已通过

- Maven package 成功；33 项测试通过，0 失败，0 跳过。包含文档解析、分块、请求鉴权、上传去重、重试/删除、引用校验、RRF、Qdrant REST 合同和真实 Qdrant 临时 collection 的写入/查询/删除。
- Spring AI 模型适配器使用本机 HTTP 测试桩验证 Embedding、聊天和流式协议；测试不消耗云端额度。
- 实际上传两份 samples 文档，异步处理至 READY。检索冒烟集 6/6 通过，实际模式 LEXICAL。上传返回 QUEUED，评测须等待 READY 后执行。
- 使用本地忽略配置中的用户凭据，实际调用 DeepSeek 模型列表并选用 deepseek-flash；通过本服务 /api/v1/chat 成功返回“每晚 650 元”，带有效 [1] 引用，grounded=true。
- 浏览器页面实际提交问题，SSE 流式回答完成，展示“每晚 650 元”和原文引用，上下文计数更新为 2/20。
- 本机服务健康检查正常，chatConfigured=true、vectorHealthy=true。

## 未完成的环境能力

- 当前未配置真实 Embedding 服务，因此实际文档仍是关键词索引。Qdrant 连通与测试通过不代表已运行语义向量检索。接入 Embedding 后需重新索引文档，再执行 HYBRID 模式评测。
- 未配置模型重排；采用内置排序。未实现 OCR、用户级文档权限和多实例调度。
- Docker/生产 PostgreSQL 部署未实跑，需要在目标环境验收。
- 六道演示问题仅为冒烟检查，不代表企业数据召回率、答案准确率或并发性能。grounded 表示引用编号有效，不保证事实正确。

## 复现

从根目录启动 scripts/Start-Local.ps1，上传 samples 中两份 Markdown，等待状态 READY，再运行 python scripts/evaluate.py。更多命令见根 README。

重新打包之前先运行 scripts/Stop-Local.ps1，避免 Windows 上运行中的 JAR 文件锁导致 Maven repackage 失败；然后重新启动。Qdrant Live 测试需先用 Start-Local.ps1 -QdrantOnly 启动数据库，并设置 RAG_TEST_QDRANT_URL。

