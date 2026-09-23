# 验证记录

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

