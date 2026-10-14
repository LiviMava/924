# 04 · 双路召回：关键词与向量各找什么

本节在相同分块上运行两种真实检索。生产也是 BM25 与向量双路召回。

| 分支 | 原理 | 输入 | 输出分数 | 特点 |
|---|---|---|---|---|
| 关键词 | Lucene SmartChineseAnalyzer 分词与 BM25：词频、逆文档频率、长度归一化 | 问题原文与本轮全部片段 | BM25，非固定区间 | 适合明确术语、型号、名字；没有词命中就返回空 |
| 向量 | 同一 Embedding 模型编码问题；Qdrant 查询 | 问题向量与已入库文档向量 | Cosine，范围 [-1,1] | 可能找到同义改写；相似不保证能回答 |

两路各自取 Top-K。BM25 与 Cosine 不同量纲，不能直接相加，也不是正确率。

## 运行

```powershell
# 双路：需要真实 Embedding，显示两列候选
.\scripts\Invoke-RagTutorial.ps1 -Stage retrieve -RecallMode hybrid -Query '去上海出差，酒店每晚最高能报销多少钱？'
# 关键词：仅解析、分块和 BM25，无需模型/向量入库
.\scripts\Invoke-RagTutorial.ps1 -Stage retrieve -RecallMode keyword -Query '上海酒店'
# 向量对照
.\scripts\Invoke-RagTutorial.ps1 -Stage retrieve -RecallMode vector -Threshold 0.99
```

API：`POST /api/v1/tutorial/runs/{id}/retrieve`，请求 `{query,topK,threshold,mode}`，mode 为 `hybrid`（默认）、`keyword` 或 `vector`。输出 `keywordHits`、`vectorHits`、问题向量摘要及 Cosine 复算。`hits` 兼容字段在双路模式下仍代表向量结果，不表示两路融合。

## 观察

1. 用完整术语提问，再换一种说法，观察两路排名是否不同。没有差异也应如实展示。
2. 将向量阈值调到 0.99，观察关键词结果不会受该阈值影响。
3. 搜索完全不存在的词，关键词返回空；不会用零分文档补足 Top-K。
4. 查看两列是否命中相同片段。随后需要按片段 ID 去重并融合排名，不能把两个分数直接求和。

`Cosine(q,d)=点积(q,d)/(范数(q)×范数(d))`。页面保留 Qdrant 分数与本地复算，帮助理解数值。小数据集可能采用精确扫描，此实验不声称验证了大规模近似检索性能。

关键词检索使用本轮全部分块建立独立内存 Lucene 索引，使用与生产一致的分词器、默认 BM25 参数和词项查询方式，不搜索其他教学会话或正式知识库。向量复用生产 `QdrantIndex.searchWithVector()`。
