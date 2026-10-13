# 02 · 分块：怎样把长文档变成候选证据

模型有上下文上限，检索需要合适的粒度。一个片段最好覆盖一个可回答的问题。过长会混入无关内容，过短可能把“上海”“住宿”和“650 元”分开。

## 运行与对照

```powershell
.\tutorial\demos\02-chunk.ps1 -Size 220 -Overlap 40
.\tutorial\demos\02-chunk.ps1 -Size 800 -Overlap 120
```

输入为解析得到的 sections 和 `size/overlap`。输出为实际 TextChunk、长度、页码/章节，以及与上一片段相同的前缀长度。[实测快照](outputs/chunk.json)

```text
section.text → chunk 0：前面的正文 + 边界文字
             → chunk 1：边界文字 + 后面的正文
```

网页高亮显示共享前缀，可以看到重叠不是“复制整段”，而是帮助边界内容保留上下文。生产默认是 800/120；教学使用 220/40，使片段更容易展示。

## 原理和实现

项目 `Chunker` 优先在段落、换行、句号处断开，不能跨 PDF 页或 Word 章节。长度单位是 Java UTF-16 字符，并非模型 Token。它也会避开 Unicode 代理对的切割位置。

```json
{"size":220,"overlap":40}
```

这是你实际提交的输入。输出中 `sharedPrefixCharacters` 是去除首尾空白后可见的重叠，因此可能小于 40。片段的 ID 会在后续入库时稳定关联到实验和片段序号。

讲师提问：片段变大之后，数量为什么减少？同一个片段是否同时包含北京和上海？大 overlap 是否增加重复候选和 Embedding 成本？没有通用的最佳分块长度，应根据文档与问题评测。
