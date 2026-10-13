# 03 · Embedding 入库：文字怎样进入向量数据库

Embedding 将文本编码为数值数组。相同模型下的语义相近文本通常更容易被相似度检索找到；这并不意味着某一维就是“住宿”或“上海”。这里没有训练或微调聊天模型。

## 运行

```powershell
.\tutorial\demos\03-embedding-store.ps1
```

输入是实际分块文本和模型配置。当前本机调用智谱 `embedding-3`，返回实际 2048 维向量，然后把它们写入真实 Qdrant。[实测快照](outputs/embed.json)

输出包含：每个片段的 ID、实际维度、前 8 维、L2 范数、最小/最大值、collection 名、实际 pointsCount 和 payload 示例。页面展示的 8 维只是数组截取，数据库存的是全部维度。

```text
片段文本 → Embedding API → float[2048]
                         ↓
Qdrant point = id + 完整 vector + payload(documentId, text)
```

## 可观察的事实

“已返回向量”与“已写入向量库”是两个动作。演示同时展示模型输出和数据库查询回来的点数。项目会校验数量、维度、有限数值和非零向量；错误不能被当作入库完成。

复用 `ModelGateway.embed()` 与 `QdrantIndex.addWithEmbeddings()`。后者接收已经生成的向量，避免为展示输出再次调用模型。每轮 collection 有独立 `rag_tutorial_` 前缀，并附带模型配置签名；公司知识库保持隔离。

讲师提问：如果把维度改为 1024，数据库为什么不能继续接收 2048 维数组？只修改配置不会自动压缩向量。换模型后也需要重新生成已有片段的向量。
