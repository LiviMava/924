# 06 · 重排：从“有点相关”到“能回答这个问题”

重排读取问题和已有候选，重新排列相关性。它无法找回没有被召回的文档，必须先保证候选中存在正确依据。[Cohere 的候选重排介绍](https://docs.cohere.com/docs/rerank-overview)

## 独立演示

```powershell
.\tutorial\demos\06-rerank.ps1 -RerankMethod configured
.\tutorial\demos\06-rerank.ps1 -RerankMethod llm
.\tutorial\demos\06-rerank.ps1 -RerankMethod bm25
```

独立 demo 的实际输入固定为以下候选，目的是直观看城市和费用类别，不是伪装成真实召回排名：

| 初始序号 | 候选内容 |
|---|---|
| 0 | 上海办公室会议餐饮预算 80 元 |
| 1 | 北京出差酒店住宿上限 550 元 |
| 2 | 上海出差酒店住宿上限 650 元 |
| 3 | 报销须在 15 个工作日内提交 |

问题是上海酒店限额，候选 2 可以直接回答。实际智谱模型输出与前后排名见 [独立重排快照](outputs/hybrid/rerank-independent-glm.json)。完整八步链路在 RRF 融合后调用智谱重排，见 [当前实测快照](outputs/hybrid/rerank.json)。旧 LLM 快照保留为历史对照。

## 三种模式的区别

`llm` 真实调用 DeepSeek，让模型一起看整组候选，输出 `{"order":[...]}`。这属于 listwise LLM 重排；程序校验它是不是完整排列，不接受重复、越界和漏项。输出只有顺序，**没有虚构的概率分数**。[LLM 重排研究](https://arxiv.org/abs/2304.09542)

`bm25` 在本地对候选建立临时 Lucene 索引，重新计算中文关键词分数。它是离线基线，无法等同于 Cross-Encoder。

`configured` 调用项目生产 `Reranker`，发送 `query/documents/model/top_n`，接收 `index/relevance_score`。本机已配置智谱模型 `rerank`，开启 `return_raw_scores=true`，请求与配置见 [智谱重排说明](../docs/GLM_RERANK.md)。网页显示模型名、输入 RRF 分数、模型实际返回的原始相关性分数、重排前后名次。原始分数可大于 1 或小于 0，不能当概率，也不能与 RRF 直接比较或相加。

重排后顺序可能不变，也可能变差；请核对依据，而不是把“排名改变了”当成质量提升。专用模型、LLM 与不重排的选择，需要比较真实公司问题集的质量、延迟和成本。

## 分数怎样读

完整链路的输入来自融合阶段，`beforeScore` 保留 RRF 分数，`rerankScore` 是供应商返回的数值，`scoreType=RAW_RELEVANCE` 明确其语义。网页保留九位有效数字，避免接近的分数被显示成一样。

`llm` 对照只给顺序，`rerankScore=null`，页面显示“不提供”；BM25 对照明确标注 BM25。不能给 LLM 排列附上人为编造的分数。融合、重排、Prompt 的片段 ID 保持一致，引用编号在选取证据后重新分配。
