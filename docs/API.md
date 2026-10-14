# API v1 集成合同

基础URL默认为 http://127.0.0.1:8090。交互文档为 /swagger-ui.html，OpenAPI JSON为 /v3/api-docs。JSON使用UTF-8。

NX 工具模式使用独立的 `/api/v1/agent` 接口。工具发现、SSE 事件、确认/取消/恢复和会话 cookie 的合同见 [MCP / NX 集成](MCP_NX.md#api-集成)。普通文档 `/chat` 不会调用 NX。

## 鉴权与错误

服务配置RAG_API_KEY后，业务请求必须携带 Authorization: Bearer <token>。这是应用服务令牌，不是模型供应商API Key。系统GET /api/v1/system与GET /actuator/health公开可用。

错误使用 application/problem+json，包含status、detail与requestId；HTTP响应头也含X-Request-Id。常见状态：400参数无效、401令牌错误、403跨站写入、404文档不存在、413过大、429问答并发已满、503模型配置缺失或服务暂不可用。

## 文档

| 方法与路径 | 请求 | 返回 |
|---|---|---|
| GET /api/v1/documents?limit=100&offset=0 | limit=1–500 | 文档数组，按创建时间倒序 |
| GET /api/v1/documents/{id} | UUID | 一条文档记录 |
| POST /api/v1/documents | multipart/form-data，字段file | 202，文档记录与Location |
| POST /api/v1/documents/{id}/retry | 无body | 202，重新排队的文档 |
| DELETE /api/v1/documents/{id} | 无body | 204，仅确认删除任务已接受 |
| GET /api/v1/documents/{id}/content | 无body | 原文件附件下载 |

文档记录包含id、filename、sha256、status、chunkCount、indexMode、vectorCollection、error、createdAt、updatedAt。状态为QUEUED/PROCESSING/READY/FAILED/DELETING。indexMode为NONE/LEXICAL/HYBRID。

202不表示已经入库成功，应轮询单个文档直到READY或FAILED。相同内容上传返回现有文档ID，即使文件名不同也不重复创建。READY与FAILED可调用retry进行重建。删除立即阻止后续新检索使用该文档，物理清理由后台完成；DELETING错误保留error并自动重试。

```powershell
$headers = @{ Authorization = "Bearer $env:RAG_API_KEY" }
# PowerShell 7，Windows PowerShell 5.1可使用curl.exe或其他HTTP客户端
Invoke-RestMethod http://127.0.0.1:8090/api/v1/documents -Method Post -Headers $headers -Form @{ file = Get-Item .\samples\company-handbook.md }
```

## 独立检索

POST /api/v1/search

```json
{"query":"上海出差的住宿报销标准？","topK":6}
```

query长度1–2000字符，topK范围1–12，默认6。返回：

可选 `documentId` 为文档 UUID；传入后，BM25 和向量检索都在获取 Top-K **之前**限定该文档。不传则搜索整个知识库；文档不存在或未 READY 时返回空命中和警告。该参数是检索范围，不是用户权限边界。

```json
{
  "query":"上海出差的住宿报销标准？",
  "hits":[{
    "id":"chunk-uuid",
    "documentId":"document-uuid",
    "filename":"company-handbook.md",
    "page":null,
    "section":"文档",
    "text":"……住宿报销上限为每晚650元……",
    "score":0.0327
  }],
  "retrievalMode":"HYBRID",
  "warnings":[]
}
```

retrievalMode可能为 NONE、LEXICAL、HYBRID 或相应的 `_RERANKED`（HTTP 服务）、`_LLM_RERANKED`（聊天模型重排）。warnings描述未配置或降级，不应在集成侧忽略。score随模式含义不同，RRF分数不能当作置信度。LLM 模式只改变顺序，保留原 RRF 分数；异常时降级到 RRF 并发出警告，不会标记为已重排。

## 问答

POST /api/v1/chat，或POST /api/v1/chat/stream。

```json
{
  "question":"上海出差住宿每晚最多能报销多少？",
  "history":[
    {"role":"user","content":"我想了解出差制度"},
    {"role":"assistant","content":"请说明你要查询的事项。"}
  ]
}
```

question长度1–2000。history可省略；最多20条，role只能为user/assistant，content每条1–8000字符、合计不超过24000。调用方负责其用户身份与会话归属；服务端不存会话。

普通和流式问答均接受可选 `documentId`，与独立检索的范围语义一致。例如 `{"question":"该机构的入职考察期多久？","history":[],"documentId":"文档 UUID"}`。多机构手册实验默认限定所选手册，避免把其他机构的制度作为答案。

普通返回：

```json
{
  "answer":"演示手册中规定，上海住宿上限为每晚650元。[1]",
  "citations":[],
  "grounded":true,
  "retrievalMode":"HYBRID",
  "warnings":[]
}
```

此示例省略citations内容；实际数组结构与search.hits相同，[1]指第一条、[2]指第二条，而不是UUID。grounded只反映存在有效编号引用；不能把它当作模型事实准确性的保证。

没有检索证据时返回grounded=false与明确说明，不会凭常识补齐公司政策。检索到证据但没有聊天模型配置时返回503，不会返回伪造回答。

## 流式协议

使用POST JSON与fetch流式读取，不能使用只支持GET的原生EventSource：

```javascript
const response = await fetch('/api/v1/chat/stream', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
  body: JSON.stringify({ question: '报销需要哪些材料？', history: [] })
});
// 先检查response.ok，再按SSE帧边界解析response.body。
// 可复用static/rag.js中的readEventStream实现。
```

事件顺序：

1. sources：{citations,retrievalMode,warnings}。
2. delta：{text}，零个或多个。
3. done：完整ChatResponse，覆盖临时delta并确认完成。
4. 若流开始后失败，发送error：{message}，不发送done。

开始流之前的校验/配置失败返回非2xx ProblemDetail。连接结束但没有done不是成功。最终引用校验可能替换临时答案，因此集成侧必须以done.answer为最终内容。客户端取消后不应将未完成答案计入历史。

## 可观测性

GET /actuator/health用于应用/数据库健康检查；GET /api/v1/system额外返回vectorHealthy与模型“是否配置”（不代表凭据已经通过云端验证）。配置服务令牌时GET /actuator/prometheus也需要同一令牌。自定义指标包含rag.retrieval.duration与rag.retrieval.fallback，Spring AI模型调用接入Micrometer Observation。

健康检查成功不表示问答模型可用；部署验收应包含一次真实文档上传与问答。
## RAG 教学实验 API

接口前缀 `/api/v1/tutorial`，需 `rag.tutorial.enabled=true`。沿用服务 Bearer；所有 run 必须使用创建它的同一会话 cookie。教学页面静态文件可公开读取，实验数据与操作接口仍按 API 鉴权。

| 路径 | 输入 / 作用 |
|---|---|
| GET /config | 配置状态与教学限制，不返回密钥 |
| GET /handbooks | 本地公开手册目录、当前入库状态、问题集与最近一次真实评测结果 |
| GET /samples/{md\|docx\|pdf} | 下载真实示例文件 |
| POST /runs | `{sample:"md"}` 创建实验，返回 runId |
| POST /runs/upload | multipart `file`；教学上传上限 2 MiB |
| POST /runs/from-document | `{documentId:"UUID",fromPage:12,toPage:12}`；将已 READY 的 PDF 快照放入实验，仅展示连续 1–3 个物理页 |
| GET /runs/{id} | 当前实验所有已完成阶段 |
| POST /runs/{id}/parse | 真实文件解析 |
| POST /runs/{id}/chunk | `{size:220,overlap:40}` |
| POST /runs/{id}/embed | 真实 Embedding + 独立 Qdrant 入库 |
| POST /runs/{id}/retrieve | `{query:"…",topK:5,threshold:0,mode:"hybrid|keyword|vector"}` |
| POST /runs/{id}/fusion | `{query:"…",topK:10}`，复用生产 RRF，对两路片段 ID 去重，输出名次、贡献和总分 |
| POST /runs/{id}/rerank | `{query:"…",method:"llm\|bm25\|configured",source:"fused\|retrieved\|fixture"}` |
| POST /runs/{id}/prompt | `{question:"…",topK:3,source:"ranked\|fused\|retrieved\|fixture"}` |
| POST /runs/{id}/generate | 使用刚组装的 Prompt 调用真实聊天模型 |
| DELETE /runs/{id} | 清理本实验向量、上传的临时文件和状态 |

每步统一返回 `runId/stage/mode/input/output/notes/elapsedMs`。无前置数据或问题已变化返回 409；参数无效返回 400；其他会话返回 404；已过期返回 410。LLM 重排输出完整候选排列，不输出伪造分数；BM25 模式明确标为关键词基线。详细说明见 [教程](../tutorial/README.md)。

`from-document` 只从文档服务取得已经校验过的本地文件，客户端不能指定服务器路径。它保留原始物理页码，使用独立实验 collection；不重新上传整份手册，也不改变正式索引。来源清单默认读取 `.runtime/handbooks`，可通过 `RAG_HANDBOOK_DIR` 配置。

八步完整链路默认以 `fused` 作为重排输入。融合的 Top-K 范围为 1–24；改变召回或分块会使旧融合与后续结果失效，改变问题必须重新召回和融合。重排输出 `inputScoreType`、`scoreType`、`model`，每条 `comparison` 包含 `beforeRank/afterRank/beforeScore/rerankScore/hit`。智谱原始分数为 `RAW_RELEVANCE`；LLM 仅排列为 `ORDER_ONLY`，数值分数为空。
