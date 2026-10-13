# 04 · 向量召回：为什么换个问法也可能找到依据

入库时计算文档向量；提问时计算问题向量，两者必须来自兼容的同一个模型空间。本实验单独看向量召回，生产服务另外使用中文 BM25 和 RRF 融合。

## 运行与输入

```powershell
.\tutorial\demos\04-vector-recall.ps1 -Query '去上海出差，酒店每晚最高能报销多少钱？' -TopK 5 -Threshold 0
.\tutorial\demos\04-vector-recall.ps1 -Query '去上海出差，酒店每晚最高能报销多少钱？' -TopK 5 -Threshold 0.99
```

```json
{"query":"去上海出差，酒店每晚最高能报销多少钱？","topK":5,"threshold":0}
```

输出是问题向量摘要、实际召回片段、Qdrant 分数与本地复算的 Cosine。[实测快照](outputs/retrieve.json)

## 原理

```text
Cosine(q, d) = Σ(q_i × d_i) / (√Σq_i² × √Σd_i²)
```

分数衡量向量方向的接近程度，不是“答案有 80% 正确”。Top-K 是最大候选数量，阈值可以让返回数量少于 K。数据集很小时，Qdrant 也可能采用精确扫描；不能仅凭使用向量数据库就宣称本例验证了大规模近似检索性能。

代码复用 `QdrantIndex.searchWithVector()`，使用 `/points/query`。本地复算只用于让听众看懂分数，不替代数据库召回。

试着提高阈值或问资料没有涵盖的问题。即使有相似片段，也不代表片段能回答问题；重排和证据检查仍然需要。网页同一实验中的相同问题会缓存问题向量，修改阈值不会重复调用 Embedding。
