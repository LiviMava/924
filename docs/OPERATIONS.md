# 运维与交接

本文对应当前项目脚本和配置。Windows 本地运行采用 JDK 17、H2 文件数据库、Lucene 和原生 Qdrant 1.19.1；Linux Compose 参考配置使用 PostgreSQL 17、Qdrant 和应用容器。两种运行方式的数据目录独立，不会自动迁移。

## Windows 安装、启动与停止

在项目根目录打开 PowerShell，先检查 JDK 与 Maven：

```powershell
$env:JAVA_HOME
& "$env:JAVA_HOME\bin\java.exe" -version
mvn -version
```

`JAVA_HOME` 必须指向 **JDK 17**。本机曾出现 `JAVA_HOME` 是 17、系统 `PATH` 中 `java` 却是 11 的情况，所以不能只依据 `java -version` 判断。启动脚本直接使用 `JAVA_HOME\bin\java.exe`，构建时也临时把该 JDK 放在 PATH 前面；不会修改系统 PATH。

首次复制配置并编辑 `.local.env`，已有该文件时保留原配置：

```powershell
if (-not (Test-Path -LiteralPath .local.env)) {
    Copy-Item -LiteralPath .env.example -Destination .local.env
}
```

文件只接受 `NAME=VALUE`、空行与整行注释。值不会作为 PowerShell 执行，也不会展开 `$变量`；不要在值末尾追加注释。`.local.env` 和 `.runtime` 已排除在版本控制之外。修改配置后需要停止再启动，重复执行启动命令不会自动重启已有进程。

```powershell
# 一键安装依赖、Maven 构建并启动
.\scripts\Start-Local.ps1

# 仅安装固定版本 Qdrant；可重复执行
.\scripts\Install-Local.ps1

# 已有 target/java-chatbot-1.0.0.jar 时跳过构建
.\scripts\Start-Local.ps1 -SkipBuild

# 只启动 Qdrant，不启动应用
.\scripts\Start-Local.ps1 -QdrantOnly

# 停止本项目记录并核验归属的进程，保留数据
.\scripts\Stop-Local.ps1
```

安装脚本从官方发布下载 Windows x64 Qdrant ZIP，固定版本与 SHA-256，重复安装校验已有文件。启动脚本以隐藏窗口运行服务，通过可执行路径、命令行标记、启动时间与 PID 记录确认归属；遇到其他进程占用端口会停止操作，不会杀掉该进程。受限执行环境若禁止读取 Windows 进程信息，需允许相应操作后重试。

脚本将应用固定在 `http://127.0.0.1:8090`，Qdrant HTTP/gRPC 固定在 `127.0.0.1:6333/6334`，使用 `local` profile，工作目录固定为项目根目录。当前向量操作走 Qdrant HTTP `/points/query`；6334 保留但当前检索实现不依赖它。

Maven 使用项目内 `infra/maven-settings.xml`，同时传入 `-gs` 与 `-s`，屏蔽机器上的其他镜像配置；依赖缓存固定在 `.runtime/m2`，仓库为 HTTPS Maven Central。首次构建与安装需要可用网络。

## 模型和访问令牌

**未配置模型密钥也可以运行文档管理、解析、分块和关键词检索。** 未配置 Embedding 时新文档以 `LEXICAL` 模式入库；生成回答需要可用的 Chat 模型。页面或 `/api/v1/system` 中的“已配置”仅表示所需字段齐全，不表示云端凭据或模型已经调用成功。

| 配置 | 用途与要求 |
| --- | --- |
| `RAG_API_KEY` | 本应用的 Bearer 访问令牌，与模型服务密钥无关；本地可留空，Compose 要求非空。设置后受保护 API 使用 `Authorization: Bearer <令牌>`。 |
| `RAG_CHAT_BASE_URL`、`RAG_CHAT_API_KEY`、`RAG_CHAT_MODEL` | Chat 三项配置，负责生成回答。示例沿用 `https://api.deepseek.com` 与 `deepseek-v4-flash`，需根据实际账号可用模型调整。 |
| `RAG_EMBEDDING_BASE_URL`、`RAG_EMBEDDING_API_KEY`、`RAG_EMBEDDING_MODEL` | 独立配置的 Embedding 三项，负责文档与问题的向量化；不会自动复用 Chat 配置。 |
| `RAG_EMBEDDING_DIMENSIONS` | 必须等于 Embedding 模型实际返回的向量长度。示例 `1024` 只是项目默认值。 |
| `RAG_RERANK_URL`、`RAG_RERANK_API_KEY`、`RAG_RERANK_MODEL` | 可选重排。URL 填完整重排接口地址；不启用时保留空值。 |
| `RAG_QDRANT_COLLECTION` | collection 前缀，默认 `company_chunks_v1`；只允许字母、数字、下划线、连字符，长度 1–100。 |

模型 URL 填供应商的 OpenAI 兼容 API 基础地址，不填单次请求的 `/chat/completions` 或 `/embeddings` 末端路径；是否包含 `/v1` 以实际供应商兼容接口为准。重排 URL 则是完整请求地址。

当前实现将“基础地址、密钥、模型名均非空”视为启用模型。对明确不需要鉴权的自建兼容服务，可使用其允许的非空占位密钥，例如 `not-required`；它不能替代云供应商的真实密钥。项目不会自动生成、购买或申请模型服务密钥。Chat 配置还保留了 `DEEPSEEK_API_KEY` 环境变量回退，因此排查是否启用时也应考虑已有进程环境。

维度配置用于创建 collection 和检查模型返回值，**当前不会把它作为降维参数发送给 Embedding API**。如模型固定输出 1536 维，就应填写 1536；不要把配置改为 1024 来期待模型自动压缩。首次接入应使用少量真实文档验证：文档达到 `READY`、`indexMode=HYBRID`、检索来源符合预期，再扩大导入量。

本项目交付未实测上述云模型，示例值不代表已验证的供应商组合。

## 数据、日志与检查

以下路径相对于项目根目录：

| 路径 | 内容 |
| --- | --- |
| `.runtime/data/metadata.mv.db` 及同目录 H2 相关文件 | 本地文档元数据、分块、入库任务状态等权威记录 |
| `.runtime/data/uploads/` | 上传原文件，内部使用文档 UUID 文件名 |
| `.runtime/data/lucene/` | 本地关键词索引；启动时从数据库中的 READY 分块重建 |
| `.runtime/data/tmp/` | 上传处理临时文件 |
| `.runtime/qdrant/storage/` | 本地向量数据库持久数据 |
| `.runtime/qdrant/snapshots/` | Qdrant 快照目录；不会仅因目录存在就自动产生备份 |
| `.runtime/qdrant/tmp/` | Qdrant 临时文件 |
| `.runtime/logs/app.stdout.log`、`app.stderr.log` | 应用标准输出、错误日志 |
| `.runtime/logs/qdrant.stdout.log`、`qdrant.stderr.log` | Qdrant 日志 |
| `.runtime/pids/` | 本项目进程归属记录；不是可恢复数据 |
| `.runtime/downloads/`、`.runtime/m2/` | 安装包与 Maven 缓存 |

原生启动会重用日志文件，重启前应另存需要留档的日志。Qdrant 原生 ZIP 不包含 Dashboard 静态页面；日志中的静态目录不存在提示不影响 HTTP 向量服务。

```powershell
Invoke-RestMethod http://127.0.0.1:8090/actuator/health
Invoke-RestMethod http://127.0.0.1:8090/api/v1/system
Invoke-WebRequest http://127.0.0.1:6333/readyz -UseBasicParsing
Get-Content .runtime\logs\app.stdout.log -Tail 80
```

`/actuator/health` 反映应用健康，不等于外部 Chat/Embedding 已经验证成功。模型配置或维度问题应结合文档状态、失败说明和日志定位。当前支持 PDF、DOC、DOCX、TXT、MD，单文件上限 20 MiB；图片及扫描件 OCR 尚未启用。

## Linux Compose 参考部署

此配置尚未在本机实测 Docker 构建和三服务运行。需准备 Docker Engine 与 Compose 插件，在项目根目录复制并配置 `.local.env`。Compose 会要求非空 `RAG_API_KEY`、`RAG_DB_PASSWORD`、`RAG_QDRANT_API_KEY`，以及 Chat/Embedding 的基础地址、模型名和向量维度；当前 Compose 启动约束比 Windows 的无模型模式更严格。

```bash
# 首次使用；已有 .local.env 时保留原文件
test -f .local.env || cp .env.example .local.env

# 编辑 .local.env 后，准备非 root 应用的可写数据目录
mkdir -p .runtime/compose/app
sudo chown 10001:10001 .runtime/compose/app

docker compose --env-file .local.env up -d --build
docker compose --env-file .local.env ps
docker compose --env-file .local.env logs --tail=100 app

# 停止，保留项目内的绑定目录
docker compose --env-file .local.env stop
```

应用镜像用 Maven/JDK 17 构建，以 JRE 17 和 UID/GID `10001:10001` 运行。PostgreSQL 使用 `postgres:17-alpine`；它的初次初始化入口会处理数据库绑定目录，通常无需把它改成应用的 UID。恢复已有目录时应分别保留 PostgreSQL 和应用文件的正确所有权。

| 主机绑定目录 | 容器用途 |
| --- | --- |
| `.runtime/compose/postgres/` | PostgreSQL 数据目录 |
| `.runtime/compose/app/` | 应用 `/app/data`，包含 uploads、Lucene 和临时文件 |
| `.runtime/compose/qdrant/storage/` | Qdrant 数据 |
| `.runtime/compose/qdrant/snapshots/` | Qdrant 快照 |

容器标准输出使用 Docker 日志驱动，按 10 MB × 3 个文件轮转，不写入原生 Windows 的 `.runtime/logs`。应用只映射主机 `127.0.0.1:8090`；PostgreSQL 与 Qdrant 不映射主机端口。需要让公司网络访问时，在此端口前配置现有的 HTTPS 入口。模型服务若位于 Docker 主机，可按实际服务使用 `host.docker.internal`，不要在容器配置中用 `localhost` 指代主机。

**当前应用只支持单实例部署。** Lucene 独占索引目录，上传文件也保存在本地；即使把 H2 换成 PostgreSQL，也不能直接执行 `--scale app=2` 或增加 Kubernetes 副本。多实例需要先调整文件存储、检索服务与任务协调。

## 备份与恢复

备份须覆盖同一逻辑时点的数据库、上传原文件与向量数据。只有 Qdrant 快照或只有数据库都不足以完整恢复。Lucene 是派生索引，可从数据库分块重建。

### Windows / H2

1. 先停止导入和问答写入，等待入库/删除任务完成。
2. 如采用 Qdrant 快照，先在暂停写入期间用 Qdrant 的 collection snapshot API 为所有实际使用的 collection 创建快照，并确认完成；迁移模型后可能同时存在多个 collection。快照目录应随备份保存。
3. 运行 `Stop-Local.ps1`，确认应用和 Qdrant 已停止，再复制完整 `.runtime/data/`、`.runtime/qdrant/storage/` 与 `.runtime/qdrant/snapshots/` 到备份位置。H2 必须在停止后完整保存相关数据库文件，不能仅复制运行中的 `metadata.mv.db`。
4. 另存对应的软件版本、模型/维度/collection 配置；密钥由现有凭据管理方式保存。PID 文件和缓存不需要恢复。

恢复时保持服务停止，把同一备份批次的数据放回上述目录；冷备 Qdrant storage 与 snapshot 恢复二选一，snapshot 应按 Qdrant 对应版本的恢复流程导入，不要只把文件丢进目录就认为已恢复。启动后检查文档状态、原文件下载、关键词和向量检索。不要同时启动旧副本写入同一数据目录。

### Linux / PostgreSQL

1. 停止应用容器以冻结文档写入，保留 PostgreSQL、Qdrant 运行。
2. 用 `pg_dump` 保存数据库一致性导出，并备份 `.runtime/compose/app/uploads/`。例如在已配置 `.local.env` 的项目目录执行：

   ```bash
   docker compose --env-file .local.env stop app
   mkdir -p backups
   docker compose --env-file .local.env exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > backups/rag.dump
   ```

3. 为数据库文档记录中涉及的所有 Qdrant collection 创建并保存快照。保持应用停止，直到 DB dump、uploads 和向量快照均保存完成；再启动应用。
4. 恢复到空数据库时使用 `pg_restore`，恢复同一批 uploads 与 Qdrant 快照，并将应用目录权限还原给 `10001:10001`。恢复后启动应用，由数据库重建 Lucene，再抽查检索和原文件下载。

这里的 `pg_dump` 方案要求 PostgreSQL 继续运行；如果选择文件级冷备，则应连 PostgreSQL、Qdrant 一起停止后完整保存各自数据目录，不要复制运行中的 PostgreSQL 目录作为一致性备份。跨 PostgreSQL 主版本迁移使用导出/恢复或经验证的升级流程，不能仅替换镜像版本复用旧目录。

## 模型升级与重新索引

先备份，再停止应用、修改 `.local.env` 并重启。Chat 模型变化不要求重做向量；Embedding 的基础 URL、模型名或维度变化会影响向量空间。

应用用 `Embedding 基础 URL + 模型名 + 维度` 计算签名，实际 collection 为 `RAG_QDRANT_COLLECTION_<12位签名>`，因此常规配置变更会进入新 collection。**签名隔离不等于旧文档会自动迁移。** 原来仅做关键词入库的文档、旧模型的文档，都需要逐份重新索引。

当前入口是 `POST /api/v1/documents/{id}/retry`，适用于 `READY` 或 `FAILED` 文档；页面中的重新索引操作使用此入口。提交后等待文档依次从 `QUEUED`、`PROCESSING` 回到 `READY`，检查其 `indexMode` 为 `HYBRID`、`vectorCollection` 属于当前签名。未完成迁移前，部分文档可能只能通过关键词召回。

如果供应商在相同模型名、相同 URL 和相同维度下更换了模型权重，配置签名无法察觉；应同时改用新的 `RAG_QDRANT_COLLECTION` 前缀后重新索引。不要提前删除旧 collection；确认全部迁移完成、没有文档引用且已保留备份后再清理。仅恢复备份而没有改变模型配置时，无需强制调用云模型重建全部向量。

## 交付边界

Windows Qdrant 下载校验、健康检查、loopback 监听与重复启动幂等性已经实际验证。Docker/Compose 仍是部署参考，未完成真实容器环境验收；云 Chat、Embedding、重排服务需使用实际账号与数据验证。

当前鉴权是共享服务 Bearer 令牌，尚未实现公司 SSO、按用户/部门的细粒度文档 ACL 和多租户隔离。接入公司入口并不会自动获得文档级权限；这些能力需要后续实现后再按企业权限要求验收。
