# 实测输入与输出

当前八步混合检索、RRF 与智谱分数的实测记录见 [2026-10-15 八步快照](hybrid/README.md)。下面保留 2026-10-12 的七步 LLM 重排记录，作为历史对照。

记录日期：2026-10-12，Windows / 当前 Java 项目。资料全部为虚构。以下 JSON 来自真实教学 API，不是手工写出的模型结果；本轮 CLI 实验结束后已删除对应的教学 collection，名称留在快照中便于追踪。

| 步骤 | 本次观察结果 | 完整输入 / 输出 |
|---|---|---|
| Markdown 解析 | 1 个正文 section，791 字符 | [parse.json](parse.json) |
| Word 解析 | 7 个 section，包含真实表格“上海\t650”，页码为空 | [parse-docx.json](parse-docx.json) |
| PDF 解析 | 两页英文文本，物理页码 1、2 | [parse-pdf.json](parse-pdf.json) |
| 分块 220/40 | 6 个片段，保留来源和可见重叠 | [chunk.json](chunk.json) |
| 分块 800/120 | 1 个片段 | [chunk-800.json](chunk-800.json) |
| Embedding 入库 | 智谱 embedding-3，实际 2048 维，Qdrant 6 个点 | [embed.json](embed.json) |
| 向量召回 | Top-K=5，返回 5 条；第一条 Qdrant 分数 0.5751498，本地复算 0.5751497241 | [retrieve.json](retrieve.json) |
| 高阈值对照 | 阈值 0.99，返回 0 条 | [retrieve-threshold-099.json](retrieve-threshold-099.json) |
| 完整链路 LLM 重排 | 候选原名次 4、2、3、1、5；原第 4 条成为第一条 | [rerank.json](rerank.json) |
| 独立候选集 LLM 重排 | 上海住宿 650 元的候选 `fixture-2` 排第一 | [rerank-independent.json](rerank-independent.json) |
| 离线 BM25 对照 | 本地关键词重排，明确不标为神经网络 | [rerank-bm25.json](rerank-bm25.json) |
| Prompt | 共享生产组装器，选 3 条证据，展示 system/user 消息 | [prompt.json](prompt.json) |
| 回答 | DeepSeek 实际返回“每人每晚最高 650 元”，引用编号有效 | [generate.json](generate.json) |

这组结果很适合授课：召回的第一条并不一定是最直接的答案依据，重排在本次把原第 4 条提前。它只是一次具体观察，不能据此推断所有问题都会改善，或推出企业数据上的准确率。

模型浮点值、重排顺序、延迟和回答措辞可能变化。上表不要求新运行逐字相同；应核对实际来源、城市、金额、费用类别和引用。API 的 `mode` 标明实际使用的处理方式，`factsVerified=false` 提醒引用有效不等于逐句事实核验。

## 重现和验证

```powershell
.\scripts\Test-RagTutorial.ps1 -Offline # 本地解析、分块、关键词重排和 Prompt
.\scripts\Test-RagTutorial.ps1          # 另加真实 Embedding/Qdrant/LLM 完整流程
```

脚本使用最新实际输出断言页码、表格、分块变化、点数、Cosine、回答关键事实和引用；只对虚构资料做冒烟检查。更完整的自动回归覆盖在 `TutorialServiceTest`、`TutorialApiIntegrationTest`、`PromptAssemblerTest` 中，云端为测试桩，真实云端结果由上述脚本单独验收。
