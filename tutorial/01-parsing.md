# 01 · 文档解析：文件怎样成为文字

文件上传完成，只意味着拿到了字节。解析器要识别文件格式，提取文字，并保留来源位置。向量模型需要文字，引用需要位置，两者都从这里开始。

## 运行

```powershell
.\tutorial\demos\01-parse.ps1 -Sample docx
.\tutorial\demos\01-parse.ps1 -Sample pdf
```

网页中准备 Word/PDF 后点击“文档解析”。“下载原文件”可展示原来的章节和表格。

## 输入与输出

输入是实际 DOCX 或 PDF 文件。DOCX 会保留章节，表格每行转为文字，单元格之间以 `\t` 分隔；`page=null`，因为 Word 页码取决于排版。两页 PDF 会输出 `page=1` 和 `page=2`，不是编造页码。

输出结构示例（完整实际结果见 `parse.json` 和 [Word 解析快照](outputs/parse-docx.json)）：

```json
{"text":"……上海\t650……","page":null,"section":"限额汇总表"}
```

这只是单条 section 的结构，段落数量和字符数读取实际 JSON。现场可以先展示 Word 汇总表，再在输出中查找“上海\t650”。

## 代码与检查

复用 `DocumentParser.parse(path, filename)`，PDFBox 处理文本型 PDF，POI 处理 Word，UTF-8 解码处理 Markdown/TXT。PDF 按物理页拆成 section，Word 按标题拆成 section。Markdown 当前作为一个正文 section，不会自动按标题建立结构树。

重要检查：扩展名与文件头一致；没有乱码；段落和表格顺序正确；PDF 页码可追溯。扫描 PDF 没有文字层，需要 OCR；本项目明确拒绝，不返回“已解析成功”的假结果。
