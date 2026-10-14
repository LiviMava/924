# 知序 · 企业内部文档问答基座

在现有 Java 聊天项目上扩展的 RAG 服务。支持 PDF、Word、TXT、Markdown 导入，中文关键词与向量混合检索、可选模型重排、带出处回答、REST API 和网页管理台。文档、数据库、依赖缓存与日志保存在当前项目目录；模型通过可替换的云端兼容 API 调用。

## 快速启动（Windows）

要求已有 **JDK 17**（JAVA_HOME 指向 JDK）和 Maven。无需安装 Docker、Python 或 Node 才能运行应用。

在此目录执行：

```powershell
Copy-Item .env.example .local.env
# 编辑 .local.env，填写聊天和 Embedding 的独立配置；不要提交密钥。
.\scripts\Start-Local.ps1
```

启动脚本会校验并安装固定版本 Qdrant、运行测试打包、在后台启动服务。再次执行不会重复启动进程。

- 管理台：[http://127.0.0.1:8090](http://127.0.0.1:8090)
- 交互式接口文档：[http://127.0.0.1:8090/swagger-ui.html](http://127.0.0.1:8090/swagger-ui.html)
- 健康检查：[http://127.0.0.1:8090/actuator/health](http://127.0.0.1:8090/actuator/health)
- 停止：`./scripts/Stop-Local.ps1`
- 已构建时启动：`./scripts/Start-Local.ps1 -SkipBuild`
- 仅启动数据库：`./scripts/Start-Local.ps1 -QdrantOnly`

第一次拉取 Maven 依赖需要网络。运行产物全部位于 `.runtime/` 和 `java-chatbot/target/`，不修改全局 Java/Maven 配置。安装脚本下载官方 Qdrant Windows ZIP 并核验固定 SHA-256。

## 配置与能力边界

| 配置情况 | 可使用能力 |
|---|---|
| 不配置任何模型 | 上传、解析、分块、文档管理、中文关键词检索；不会伪造模型答案 |
| 配置聊天模型 | 基于检索片段进行问答，并返回引用 |
| 再配置 Embedding | Qdrant 向量检索＋Lucene BM25，通过 RRF 融合 |
| 再启用模型重排 | 配置 rerank HTTP 服务，或设置 `RAG_RERANK_MODE=llm` 使用聊天模型选择 Top-K 候选 |

聊天、Embedding、重排可以来自不同供应商。DeepSeek 等聊天端点不能自动当成 Embedding 端点。Embedding 的 URL、模型名、密钥、实际输出维度均需正确配置，具体变量见 [.env.example](.env.example)。

当前本机已接入智谱 `embedding-3`（2048 维），演示文档已重新索引为 HYBRID。新环境可按 `.env.example` 的智谱配置填写自己的 Embedding 密钥；聊天仍使用 DeepSeek。相关片段会分别发送到配置的 Embedding 和聊天 API，原文件和检索数据保存在本地。

先以关键词方式入库的文档，在配置 Embedding 后需要点击“重新索引”。修改 Embedding 的 URL、模型或维度也需要重新索引；程序用配置签名隔离 collection，避免新旧模型向量混用。

## 第一次体验

1. 打开“文档管理”，上传 `samples/company-handbook.md` 和 `samples/engineering-guide.md`。它们是明确标注的虚构演示资料。
2. 等待文档变为“已就绪”。
3. 在“检索调试”输入“上海出差住宿每晚最多能报销多少？”。
4. 配置聊天模型后，在“知识问答”问相同问题，查看来源片段与引用编号。
5. 上传自己的文本型 PDF、DOCX 或 DOC 后，重复上述流程。

文件最大 20 MiB；PDF 最多 500 页。当前不支持图片 OCR 和纯扫描 PDF，系统会给出明确错误。Word 引用保留章节，不虚构页码；PDF 引用使用原始物理页码。

## RAG 教程与逐步实验

打开 [RAG 教学实验室](http://127.0.0.1:8090/tutorial.html)，观察解析、分块、真实 Embedding 入库、关键词与向量召回、RRF 融合、重排、Prompt 和生成的实际输入输出。Start-Local 自动启用本地教学接口，其他启动方式默认关闭；教学索引与公司知识库隔离。

[完整讲义、八个独立 demo 和讲师安排](tutorial/README.md)。命令行完整流程：`./scripts/Invoke-RagTutorial.ps1 -Stage all`。重排默认实际调用智谱模型，并展示原始相关性分数，另有明确标注的离线 BM25 对照；本机已配置智谱 `rerank` 专用重排，选择 `configured` 即可使用，详见 [配置说明](docs/GLM_RERANK.md)。

[百份公开手册实验](evals/handbooks/README.md)提供 100 个固定版本的 PDF 来源、断点下载/入库脚本，以及 9 道检查事实和引用页码的真实问答回归。教学页可以从任意已入库手册选择 1–3 页运行八步，也可以对完整手册提问。原始第三方 PDF 保留在本地，仓库保存来源和校验值。

## 技术选择

| 层 | 实现 |
|---|---|
| 应用与模型接口 | Java 17、Spring Boot 4.1.1、Spring AI 2.0.1 |
| 文档解析 | Apache PDFBox 3.0.8、Apache POI 5.5.1 |
| 检索 | Qdrant 1.19.1 REST Query API、Lucene 9.12.3 SmartChineseAnalyzer/BM25、RRF |
| 元数据与任务 | Spring JDBC、Flyway；本地 H2，容器部署 PostgreSQL 17 |
| 接口与可观测性 | REST /api/v1、POST SSE、OpenAPI、Actuator、Micrometer/Prometheus |
| 界面 | Spring Boot 托管的原生 HTML/CSS/JS；无前端构建链依赖 |

保留现有 Java 技术栈。新增功能按 api/service/ingest/retrieval/store/model 分层；旧自由聊天代码保留，但旧端点默认关闭，新的问答不再把消息放进 GET URL 或无限增长的服务端会话。

## 验证

```powershell
mvn -B -gs infra/maven-settings.xml -s infra/maven-settings.xml -f java-chatbot/pom.xml "-Dmaven.repo.local=$PWD/.runtime/m2" test
# 加入真实 Qdrant 集成测试（只创建并删除专用随机测试 collection）
$env:RAG_TEST_QDRANT_URL = 'http://127.0.0.1:6333'
mvn -B -gs infra/maven-settings.xml -s infra/maven-settings.xml -f java-chatbot/pom.xml "-Dmaven.repo.local=$PWD/.runtime/m2" test
# 上传两份 samples 后可用 Python 标准库执行检索冒烟评测
python scripts/evaluate.py
```

自动测试中的模型 HTTP 服务为本机测试桩，不消耗云端模型额度。检索冒烟集只检查来源命中和关键事实出现，不等于真实企业数据上的召回率或答案准确率。上线前应以公司问题集评估检索、引用、拒答、p95 延迟与成本。

## 交接与部署

- [架构与扩展点](docs/ARCHITECTURE.md)
- [API 集成合同](docs/API.md)
- [配置、备份、恢复、部署](docs/OPERATIONS.md)
- [验证记录与限制](docs/VALIDATION.md)
- [NX 2512 MCP 接入与生产差距](docs/MCP_NX.md)：聊天中的实时建模模式、MCP Server、NX Open 桥接、确认与恢复流程。
- [容器部署参考](compose.yaml)

当前交付定位为**可运行、可测试、可集成的单实例 RAG 基座**。所有持有同一个服务令牌的调用方共享同一个知识库；尚未实现公司 SSO、用户级文档 ACL、多租户或多副本任务调度。嵌入式 Lucene 和本地文件需要单实例运行；扩容时可将关键词检索换为共享搜索服务、文件换为对象存储，并补充任务租约。

Linux Compose 使用 PostgreSQL 与 Qdrant，数据仍绑定项目目录。当前 Windows 环境没有 Docker，容器部署配置需在目标 Linux 环境验收；详细步骤和持久卷权限见运维文档。
