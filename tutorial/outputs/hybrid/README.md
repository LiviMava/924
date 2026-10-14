# 八步混合检索实测快照

验证日期：2026-10-15（Asia/Shanghai）。资料为项目自带的虚构差旅手册，以下 JSON 均来自真实教学 API；模型调用使用智谱 `embedding-3`、智谱 `rerank` 和 DeepSeek，Qdrant 为本地实例。

| 步骤 | 实测结果 | 输入输出 |
|---|---|---|
| 01 解析 | 实际提取文字与来源 | [parse.json](parse.json) |
| 02 分块 | 220/40 参数，6 个片段 | [chunk.json](chunk.json) |
| 03 Embedding | 2048 维，Qdrant 实际入库 | [embed.json](embed.json) |
| 04 双路召回 | BM25 与向量各 5 条候选 | [retrieve.json](retrieve.json) |
| 05 融合 | 两路按片段 ID 去重后 5 条，每路贡献可手算核对 | [fusion.json](fusion.json) |
| 06 智谱重排 | `RAW_RELEVANCE` 原始分数，实测范围 15.8984375–22.328125 | [rerank.json](rerank.json) |
| 07 Prompt | 选取重排后 3 条证据，展示完整消息 | [prompt.json](prompt.json) |
| 08 回答 | 实际回答包含 650 元，引用编号有效 | [generate.json](generate.json) |

[验证摘要](verification.json)检查去重、每路贡献、总分、输入 RRF 分数对应关系、重排输出数值、降序与答案/引用冒烟。脚本确认清理成功，最终部署检查另外确认该实验 collection 已不存在。

RRF、BM25、Cosine 和模型原始分数的含义各不相同，不能把不同阶段的数值直接相加或当正确率。模型排序、浮点值、延迟和回答措辞可能变化，这组快照是具体观察。

独立入口也已验证：[关键词单路融合](fusion-keyword.json)无需 Embedding，向量贡献为 0；[智谱独立重排](rerank-independent-glm.json)将上海酒店 650 元的 `fixture-2` 排到第一，分数来自真实响应。两个入口均确认清理成功。

完整流程：`./scripts/Invoke-RagTutorial.ps1 -Stage all`。自动教学验收：`./scripts/Test-RagTutorial.ps1`。真实公开手册另见 [CMU 第 12 页八步实测](../../../evals/handbooks/tutorial-run.json)，该报告核对了原文事实及物理页码。
