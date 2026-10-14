# RAG 教程：从文档到可追溯的答案

面向第一次接触 RAG 的产品、业务和开发同事。建议 45–60 分钟：先看资料怎样入库，再看一个问题怎样找到资料并形成回答。

本教程有三种入口：浏览器教学实验室、七个独立 PowerShell demo，以及逐节讲义。解析、分块、向量存取、Prompt 组装复用当前项目代码；本教程不操作 NX。

## 启动与授课

```powershell
# 项目根目录；需 JDK 17、Maven，模型配置沿用忽略文件 .local.env
.\scripts\Start-Local.ps1
# 代码更新前已经运行应用时，先仅停应用，再构建启动；Qdrant 和 NX 不需要停止
# .\scripts\Stop-Local.ps1 -AppOnly
# .\scripts\Start-Local.ps1
```

打开 [RAG 教学实验室](http://127.0.0.1:8090/tutorial.html)，或者从首页左侧进入。点击“准备示例”，依次运行七步。也可以点击“运行整条链路”。不要把真实敏感文档用于公开授课。

页面还提供 **100 份公开员工手册**：选择机构、查看原始来源、指定连续 1–3 个物理页进入七步实验，也能直接向完整手册提问。完整文件全部经过正式入库；页面选页只缩小教学展示范围。[数据来源、下载/入库/评测命令及 9 道问题](../evals/handbooks/README.md)。

Start-Local 会启用本地教学 API；其他启动方式需要 `RAG_TUTORIAL_ENABLED=true`，默认关闭。API 沿用服务 Bearer 鉴权，实验归属通过会话 cookie 绑定；页面沿用首页连接设置中的服务令牌。

```mermaid
flowchart LR
    subgraph 入库阶段
      D[PDF / Word / Markdown] --> P[01 解析]
      P --> C[02 分块]
      C --> E[03 Embedding]
      E --> V[(独立 Qdrant collection)]
    end
    subgraph 提问阶段
      Q[用户问题] --> QE[同一个 Embedding 模型]
      QE --> R[04 向量召回]
      V --> R
      R --> RR[05 重排]
      RR --> PR[06 Prompt]
      PR --> L[07 模型回答和引用]
    end
```

## 一节课的安排

| 时间 | 实验 | 让听众观察的东西 |
|---|---|---|
| 0–5 分钟 | 展示原始手册与问题 | “上海酒店每晚最高能报销多少钱？”的依据在哪一段 |
| 5–12 分钟 | [01 文档解析](01-parsing.md) | PDF 页码、Word 章节、表格、原文顺序 |
| 12–20 分钟 | [02 分块](02-chunking.md) | 220/40 与 800/120 的片段数量和重叠 |
| 20–28 分钟 | [03 Embedding 入库](03-embedding-store.md) | 文本、实际向量摘要、数据库中的点 |
| 28–35 分钟 | [04 向量召回](04-vector-recall.md) | 问题向量、Top-K、阈值、Cosine 复算 |
| 35–42 分钟 | [05 重排](05-reranking.md) | 候选排序前后，城市/费用类别带来的差异 |
| 42–48 分钟 | [06 Prompt 组装](06-prompt.md) | system/user 消息、证据编号、来源位置 |
| 48–55 分钟 | [07 生成与引用](07-generation.md) | 模型回答与原文逐项核对；引用有效与事实正确的区别 |
| 55–60 分钟 | 提问与失败案例 | 没有资料、阈值过高、模型未配置会怎样 |

## 七个独立 demo

从项目根目录执行。每次创建自己的实验，输出所有实际输入/输出到 `.runtime/tutorial/run-*/`，最后清理实验 collection。日志显示具体目录，`manifest.json` 记录完成步骤与清理状态。

```powershell
.\tutorial\demos\01-parse.ps1 -Sample docx
.\tutorial\demos\02-chunk.ps1 -Size 220 -Overlap 40
.\tutorial\demos\03-embedding-store.ps1
.\tutorial\demos\04-vector-recall.ps1 -Query '去上海出差，酒店每晚最高能报销多少钱？'
.\tutorial\demos\05-rerank.ps1 -RerankMethod llm
.\tutorial\demos\06-prompt.ps1
.\tutorial\demos\07-generate.ps1
# 完整串联；这一条才用真实召回结果作为重排输入
.\scripts\Invoke-RagTutorial.ps1 -Stage all
# 可复现验收；不联网调用模型的对照可加 -Offline
.\scripts\Test-RagTutorial.ps1
```

01、02 不调用模型；06 使用独立证据集，直接展示实际 Prompt。独立 05 使用明确编写的固定候选集，便于观察排序，**它的输入顺序不是向量召回实测排名**。03、04 自动运行必要的前置步骤；07 先组装独立证据 Prompt 再调用聊天模型。

无需模型的离线课可以执行 01、02、06，以及 `05-rerank.ps1 -RerankMethod bm25`。BM25 是关键词对照，不是神经网络重排；没有提供假的 Embedding 或假的模型答案来代替云端结果。

## 模型与重排的真实含义

当前本机 Embedding 是智谱 `embedding-3` / 2048 维，聊天是 DeepSeek。新同事必须在自己的 `.local.env` 配置密钥，仓库不会包含凭据。

重排提供三种选择：

- `llm`：实际调用配置的聊天模型，将完整候选排列输出为 JSON，校验序号完整、不重复、不越界。属于 LLM listwise 重排，不是 Cross-Encoder；没有相关性概率分数。
- `bm25`：本地 Lucene 中文 BM25，仅对候选重新计算关键词分数，用于离线对照。
- `configured`：调用生产代码 `Reranker`。`RAG_RERANK_MODE=http` 使用 Cohere 兼容 HTTP 合同，需配置 `RAG_RERANK_URL/API_KEY/MODEL`；`llm` 使用现有聊天模型选择 Top-K 候选。本机现已配置智谱 `rerank` HTTP 服务；此前 LLM 验收记录保留为历史快照。配置见 [智谱重排](../docs/GLM_RERANK.md)。

## 授课实验与观察问题

1. 用相同手册比较 Markdown、Word 和两页 PDF。PDF 示例为英文，是文本解析示例，不是中文 OCR；Word 示例额外包含汇总表。
2. 将分块从 220/40 调到 800/120。注意变更会使后续入库、召回和回答结果失效，需要重新运行。
3. 将“上海住宿上限”改为“去上海出差，酒店每晚最高能报销多少钱？”。观察语义检索；同一问题修改阈值时会复用本轮问题向量。
4. 将阈值提高到 0.99。若没有候选，Prompt 阶段会明确拒绝继续使用空证据。
5. 重排选择独立候选集。比较上海办公室会议餐饮 80 元、北京住宿 550 元、上海住宿 650 元，观察模型能否区分范围；排序不变也是有效结果。
6. 使用独立 Prompt demo，观察系统规则如何把文档放在数据区。提示词约束不是提示注入攻击的完整安全保证。
7. 提问资料没有覆盖的事情，例如公司 CEO 的手机号。模型应该说明无法依据资料确认；不要把“召回到了片段”当作“片段能回答问题”。

## 实验隔离与限制

- 每个七步实验独立 collection，前缀 `rag_tutorial_`；不修改公司索引，不调用 NX。公开手册的完整文件由导入脚本另行上传正式知识库，完整手册问答走生产检索 API。
- 最多 8 个实验、2 个并发步骤、24 个片段；直接教学上传上限 2 MiB。已正式入库的 PDF 可复用原文件（正式上传上限 20 MiB），选取连续 1–3 页；展示文字仍限 20000 字符，实验 30 分钟过期。
- 改变解析/分块会清理旧实验向量并使后续结果失效；改变问题后必须重新召回/重排。
- API 仅接受样例类型或上传文件，不接受服务端任意路径、模型 URL 或 Qdrant collection 名称。
- 点击“清理实验”或脚本结束时删除本次 collection。浏览器关闭不等于清理，服务运行时会定时清理过期实验。重启会丢失内存中的实验状态；重启前未清理的教学 collection 可能保留，需维护人员按记录的名称清理，不能删除公司 collection。
- 原文件、向量和日志在本地；Embedding 收到片段与问题，LLM 重排收到问题与候选，生成模型收到完整 Prompt。调用会产生供应商费用。
- 生产问答原本采用 BM25 + 向量 + RRF，本教程 04 刻意单独观察向量召回。教程没有声称已完成企业召回率评测。

## 输出与源码

[实测输入/输出快照](outputs/README.md) 记录本次实际运行结果；同事重新运行时应以自己的输出为准。模型浮点值、顺序和措辞可能变化，快照不是测试期待模型精确复述的答案。

后端位于 `java-chatbot/src/main/java/chatbot/chat/rag/tutorial/`；网页是 `static/tutorial.html/js/css`；原始中文资料是 `resources/tutorial/company-travel.md`。原理与代码映射见各节讲义，接口见 [API 文档](../docs/API.md)。

## 原理参考

- [Spring AI ETL](https://docs.spring.io/spring-ai/reference/api/etl-pipeline.html)：Reader、Transformer、Writer 的职责划分。
- [Qdrant Search](https://qdrant.tech/documentation/search/search/)：向量距离、召回与阈值。
- [Cohere Rerank](https://docs.cohere.com/docs/rerank-overview)：对已有候选进行重排序。
- [LLM 重排研究](https://arxiv.org/abs/2304.09542)：使用语言模型作为重排器的研究依据。
