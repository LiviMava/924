# 智谱重排配置

本机使用智谱官方端点 `https://open.bigmodel.cn/api/paas/v4/rerank`，模型 ID 为 `rerank`。2026-10-14 已使用现有智谱凭据实测。密钥复用 Embedding 的账户凭据，仅写入忽略文件 `.local.env`。

```dotenv
RAG_RERANK_MODE=http
RAG_RERANK_URL=https://open.bigmodel.cn/api/paas/v4/rerank
RAG_RERANK_MODEL=rerank
RAG_RERANK_RAW_SCORES=true
RAG_RERANK_API_KEY=填入自己的智谱密钥
```

修改后重启应用。生产流程为 BM25 + 向量 → RRF → 智谱 Rerank → Prompt → DeepSeek 生成。聊天模型不负责产生重排分数。

请求为 `{model,query,documents,top_n}`，响应读取 `results[].index` 与 `results[].relevance_score`，按返回序号映射到原候选。程序校验数量、整数索引、唯一性、范围与有限数值；失败会明确降级到 RRF 并返回 warnings，不冒充已重排。

分数是模型相关性分数，不是正确率，也不能与 BM25、Cosine、RRF 分数直接相加。默认归一化分数在实测中大量接近 1，因此本机启用官方 `return_raw_scores=true`，返回未归一化原始分数，可大于 1 或小于 0。其他 HTTP 服务默认不发送这个供应商扩展参数。应同时查看名次、原文及引用。

教程的 `configured` 选项使用该模型；`llm` 仍保留为只有排序、没有数值分数的对照。未配置专用服务时不能伪造分数。

回归：`python scripts/evaluate_handbooks.py --rerank-mode http --publish`。历史 LLM 模式可显式加 `--rerank-mode llm`；两者都不接受静默降级。

官方模型合同入口：[智谱文本重排序](https://docs.bigmodel.cn/api-reference/模型-api/文本重排序)，亦见[官方文档索引](https://docs.bigmodel.cn/llms.txt)。

验收：HTTP/LLM 合同测试 3/3 通过；启用原始分数后，原有 9 道手册问答 9/9 通过，检索与问答均为 HYBRID_RERANKED。默认归一化分数的首次 8/9 失败记录保留在本地日志。
