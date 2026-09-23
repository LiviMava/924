package chatbot.chat.rag.ingest;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Text extraction only: OCR, images, remote URLs and embedded attachments are not processed. */
@Component
public class DocumentParser {
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    public static final int MAX_PDF_PAGES = 500;
    public static final int MAX_TEXT_CHARACTERS = 2_000_000;
    public static final int MAX_SECTIONS = 20_000;
    private static final long MAX_ZIP_ENTRY_BYTES = 16L * 1024 * 1024;
    private static final long MAX_ZIP_EXPANDED_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 2048;
    private static final byte[] OLE_MAGIC = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0,
            (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};

    public List<ParsedSection> parse(Path path, String originalFilename) {
        if (path == null || originalFilename == null || originalFilename.isBlank()) {
            throw new RejectedDocument("文件及文件名不能为空");
        }
        String extension = extension(originalFilename);
        if (!List.of("pdf", "docx", "doc", "txt", "md").contains(extension)) {
            throw new RejectedDocument("仅支持 PDF、DOCX、DOC、TXT、MD；图片暂不支持");
        }
        try {
            if (!Files.isRegularFile(path)) throw new RejectedDocument("上传文件不存在");
            long fileSize = Files.size(path);
            if (fileSize == 0) throw new RejectedDocument("文件内容为空");
            if (fileSize > MAX_FILE_BYTES) throw new RejectedDocument("文件不能超过20 MiB");
            byte[] header;
            try (InputStream input = Files.newInputStream(path)) {
                header = input.readNBytes(8);
            }
            return switch (extension) {
                case "pdf" -> {
                    requireMagic(startsWith(header, "%PDF-".getBytes(StandardCharsets.US_ASCII)));
                    yield parsePdf(path);
                }
                case "docx" -> {
                    requireMagic(startsWith(header, new byte[]{'P', 'K', 3, 4}));
                    yield parseDocx(path);
                }
                case "doc" -> {
                    requireMagic(startsWith(header, OLE_MAGIC));
                    yield parseDoc(path);
                }
                default -> parseText(path, header);
            };
        } catch (InvalidPasswordException | EncryptedDocumentException e) {
            throw new RejectedDocument("不支持加密文档，请先解密后上传", e);
        } catch (RejectedDocument e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new RejectedDocument("文档无法解析，请检查文件是否损坏或格式是否正确", e);
        }
    }

    private List<ParsedSection> parsePdf(Path path) throws IOException {
        List<ParsedSection> sections = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            if (document.isEncrypted()) throw new RejectedDocument("不支持加密PDF，请先解密后上传");
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new RejectedDocument("PDF页数不能超过500页");
            }
            int total = 0;
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                LimitedTextWriter output = new LimitedTextWriter(MAX_TEXT_CHARACTERS - total);
                stripper.writeText(document, output);
                String text = normalize(output.toString());
                total += output.length();
                if (!text.isBlank()) sections.add(new ParsedSection(text, page, "第" + page + "页"));
            }
        }
        if (sections.isEmpty()) {
            throw new RejectedDocument("PDF未提取到文字，扫描件或图片PDF需要先进行OCR");
        }
        return List.copyOf(sections);
    }

    private List<ParsedSection> parseDocx(Path path) throws IOException {
        validateDocxArchive(path);
        SectionCollector collector = new SectionCollector();
        try (InputStream input = Files.newInputStream(path); XWPFDocument document = new XWPFDocument(input)) {
            for (IBodyElement element : document.getBodyElements()) {
                collector.element();
                if (element instanceof XWPFParagraph paragraph) {
                    String text = normalize(paragraph.getText());
                    String style = paragraph.getStyle();
                    if (style != null && style.toLowerCase(Locale.ROOT).startsWith("heading") && !text.isBlank()) {
                        collector.heading(text);
                    }
                    collector.append(text);
                } else if (element instanceof XWPFTable table) {
                    appendTable(table, collector, 0);
                }
            }
        }
        return collector.finish();
    }

    private void appendTable(XWPFTable table, SectionCollector collector, int depth) {
        if (depth > 8) throw new RejectedDocument("文档表格嵌套层数过多");
        for (XWPFTableRow row : table.getRows()) {
            collector.element();
            StringBuilder rowText = new StringBuilder();
            int cellIndex = 0;
            for (XWPFTableCell cell : row.getTableCells()) {
                collector.element();
                if (cellIndex++ > 0) rowText.append('\t');
                // Walk the body so nested tables do not move ahead of preceding paragraphs.
                for (IBodyElement element : cell.getBodyElements()) {
                    collector.element();
                    if (element instanceof XWPFParagraph paragraph) {
                        String text = normalize(paragraph.getText());
                        if (rowText.length() + text.length() > MAX_TEXT_CHARACTERS) textLimit();
                        if (!text.isBlank()) {
                            if (!rowText.isEmpty() && rowText.charAt(rowText.length() - 1) != '\t') rowText.append(' ');
                            rowText.append(text);
                        }
                    } else if (element instanceof XWPFTable nested) {
                        if (!rowText.isEmpty()) {
                            collector.append(rowText.toString());
                            rowText.setLength(0);
                        }
                        appendTable(nested, collector, depth + 1);
                    }
                }
            }
            collector.append(rowText.toString());
        }
    }

    private List<ParsedSection> parseDoc(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path);
             POIFSFileSystem fileSystem = new POIFSFileSystem(input)) {
            if (fileSystem.getRoot().hasEntry("EncryptedPackage")) {
                throw new RejectedDocument("不支持加密文档，请先解密后上传");
            }
            if (!fileSystem.getRoot().hasEntry("WordDocument")) {
                throw new RejectedDocument("文件内容与DOC格式不匹配");
            }
            try (HWPFDocument document = new HWPFDocument(fileSystem)) {
                SectionCollector collector = new SectionCollector();
                var range = document.getRange();
                if (range.numParagraphs() > MAX_SECTIONS) throw new RejectedDocument("文档段落数量过多");
                for (int i = 0; i < range.numParagraphs(); i++) {
                    collector.append(range.getParagraph(i).text().replace('\u0007', '\t'));
                }
                return collector.finish();
            }
        }
    }

    private List<ParsedSection> parseText(Path path, byte[] header) throws IOException {
        if (startsWith(header, "%PDF-".getBytes(StandardCharsets.US_ASCII))
                || startsWith(header, OLE_MAGIC) || startsWith(header, new byte[]{'P', 'K', 3, 4})) {
            throw new RejectedDocument("文件内容与文本格式不匹配");
        }
        byte[] bytes = Files.readAllBytes(path);
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new RejectedDocument("文本文件必须使用UTF-8编码", e);
        }
        if (text.indexOf('\u0000') >= 0) throw new RejectedDocument("文件包含二进制内容，不是UTF-8文本");
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.length() > MAX_TEXT_CHARACTERS) textLimit();
        text = normalize(text);
        if (text.isBlank()) throw new RejectedDocument("文件未提取到文字");
        return List.of(new ParsedSection(text, null, "正文"));
    }

    private void validateDocxArchive(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            if (zip.getEntry("[Content_Types].xml") == null || zip.getEntry("word/document.xml") == null) {
                throw new RejectedDocument("文件内容与DOCX格式不匹配");
            }
            Enumeration<? extends ZipEntry> entries = zip.entries();
            long total = 0;
            int count = 0;
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > MAX_ZIP_ENTRIES) throw new RejectedDocument("DOCX内部文件数量过多");
                if (entry.isDirectory()) continue;
                if (entry.getSize() > MAX_ZIP_ENTRY_BYTES) throw new RejectedDocument("DOCX解压内容过大");
                long entryTotal = 0;
                try (InputStream input = zip.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        entryTotal += read;
                        total += read;
                        if (entryTotal > MAX_ZIP_ENTRY_BYTES || total > MAX_ZIP_EXPANDED_BYTES) {
                            throw new RejectedDocument("DOCX解压内容过大");
                        }
                    }
                }
            }
        }
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static void requireMagic(boolean valid) {
        if (!valid) throw new RejectedDocument("文件内容与扩展名不匹配");
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").strip();
    }

    private static void textLimit() {
        throw new RejectedDocument("文档提取文字不能超过200万字符");
    }

    private static final class SectionCollector {
        private final List<ParsedSection> sections = new ArrayList<>();
        private final StringBuilder current = new StringBuilder();
        private String heading = "正文";
        private int total;
        private int elements;

        void element() {
            if (++elements > MAX_SECTIONS) throw new RejectedDocument("文档段落或表格单元数量过多");
        }

        void heading(String text) {
            flush();
            int end = Math.min(text.length(), 200);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            heading = text.substring(0, end);
        }

        void append(String text) {
            text = normalize(text);
            if (text.isBlank()) return;
            if (total + text.length() + 2 > MAX_TEXT_CHARACTERS) textLimit();
            if (!current.isEmpty()) current.append("\n\n");
            current.append(text);
            total += text.length() + 2;
        }

        private void flush() {
            if (current.isEmpty()) return;
            if (sections.size() >= MAX_SECTIONS) throw new RejectedDocument("文档分节数量过多");
            sections.add(new ParsedSection(current.toString(), null, heading));
            current.setLength(0);
        }

        List<ParsedSection> finish() {
            flush();
            if (sections.isEmpty()) throw new RejectedDocument("文件未提取到文字");
            return List.copyOf(sections);
        }
    }

    /** Distinguishes our user-facing rejections from parser-library argument exceptions. */
    private static final class RejectedDocument extends IllegalArgumentException {
        private RejectedDocument(String message) { super(message); }
        private RejectedDocument(String message, Throwable cause) { super(message, cause); }
    }

    private static final class LimitedTextWriter extends Writer {
        private final StringBuilder text = new StringBuilder();
        private final int limit;

        private LimitedTextWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(char[] chars, int offset, int length) {
            if (text.length() + length > limit) textLimit();
            text.append(chars, offset, length);
        }

        int length() { return text.length(); }
        @Override public void flush() { }
        @Override public void close() { }
        @Override public String toString() { return text.toString(); }
    }
}
