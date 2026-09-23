package chatbot.chat.rag.ingest;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {
    @TempDir Path temp;
    private final DocumentParser parser = new DocumentParser();

    @Test
    void pdfRetainsOneBasedPageNumbers() throws Exception {
        Path file = temp.resolve("pages.pdf");
        try (PDDocument document = new PDDocument()) {
            addPage(document, "First page: leave policy");
            addPage(document, "Second page: reimbursement policy");
            document.save(file.toFile());
        }

        List<ParsedSection> sections = parser.parse(file, "政策.PDF");
        assertEquals(2, sections.size());
        assertEquals(1, sections.get(0).page());
        assertEquals(2, sections.get(1).page());
        assertTrue(sections.get(0).text().contains("leave policy"));
        assertTrue(sections.get(1).text().contains("reimbursement policy"));
    }

    @Test
    void docxKeepsParagraphAndTableOrderAndHeadings() throws Exception {
        Path file = temp.resolve("handbook.docx");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("手册说明");
            var heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("差旅政策");
            document.createParagraph().createRun().setText("以下为报销限额：");
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("项目");
            table.getRow(0).getCell(1).setText("限额");
            table.getRow(1).getCell(0).setText("住宿");
            table.getRow(1).getCell(1).setText("500元");
            document.createParagraph().createRun().setText("请保留发票。");
            try (OutputStream output = Files.newOutputStream(file)) { document.write(output); }
        }

        List<ParsedSection> sections = parser.parse(file, "员工手册.docx");
        assertEquals(2, sections.size());
        assertEquals("手册说明", sections.get(0).text());
        ParsedSection policy = sections.get(1);
        assertEquals("差旅政策", policy.section());
        assertNull(policy.page(), "Word pagination depends on rendering and must not be invented");
        String text = policy.text();
        assertTrue(text.indexOf("以下为报销限额") < text.indexOf("项目\t限额"));
        assertTrue(text.indexOf("项目\t限额") < text.indexOf("住宿\t500元"));
        assertTrue(text.indexOf("住宿\t500元") < text.indexOf("请保留发票"));
    }

    @Test
    void imageOnlyPdfExplainsThatOcrIsRequired() throws Exception {
        Path file = temp.resolve("scan.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            var image = LosslessFactory.createFromImage(document, new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB));
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(image, 20, 20);
            }
            document.save(file.toFile());
        }
        var error = assertThrows(IllegalArgumentException.class, () -> parser.parse(file, "scan.pdf"));
        assertTrue(error.getMessage().contains("OCR"));
    }

    @Test
    void encryptedPdfIsRejectedEvenWhenOpenPasswordIsEmpty() throws Exception {
        Path file = temp.resolve("protected.pdf");
        try (PDDocument document = new PDDocument()) {
            addPage(document, "Confidential text");
            StandardProtectionPolicy protection = new StandardProtectionPolicy("owner-secret", "", new AccessPermission());
            protection.setEncryptionKeyLength(128);
            document.protect(protection);
            document.save(file.toFile());
        }
        var error = assertThrows(IllegalArgumentException.class, () -> parser.parse(file, "protected.pdf"));
        assertTrue(error.getMessage().contains("加密"));
    }

    @Test
    void textSupportsUtf8BomAndChineseButRejectsBinaryAndMismatchedExtensions() throws Exception {
        Path file = temp.resolve("source");
        Files.writeString(file, "\uFEFF# 业务知识\r\n\r\n员工可按流程申请。", StandardCharsets.UTF_8);
        var section = parser.parse(file, "说明.md").get(0);
        assertEquals("# 业务知识\n\n员工可按流程申请。", section.text());
        assertNull(section.page());
        assertThrows(IllegalArgumentException.class, () -> parser.parse(file, "伪装.pdf"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(file, "photo.png"));

        Files.write(file, new byte[]{(byte) 0xff, (byte) 0xfe, 0, 0});
        var encoding = assertThrows(IllegalArgumentException.class, () -> parser.parse(file, "binary.txt"));
        assertTrue(encoding.getMessage().contains("UTF-8"));
    }

    @Test
    void malformedPdfAndExcessivePdfPagesAreRejected() throws Exception {
        Path file = temp.resolve("invalid.pdf");
        Files.writeString(file, "%PDF-1.7\nnot a PDF", StandardCharsets.US_ASCII);
        assertThrows(IllegalArgumentException.class, () -> parser.parse(file, "invalid.pdf"));
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i <= DocumentParser.MAX_PDF_PAGES; i++) document.addPage(new PDPage());
            document.save(file.toFile());
        }
        var pages = assertThrows(IllegalArgumentException.class, () -> parser.parse(file, "large.pdf"));
        assertTrue(pages.getMessage().contains("500"));
    }

    @Test
    void rejectsTextAboveExtractionLimit() throws Exception {
        Path file = temp.resolve("large.txt");
        Files.writeString(file, "x".repeat(DocumentParser.MAX_TEXT_CHARACTERS + 1), StandardCharsets.UTF_8);
        var error = assertThrows(IllegalArgumentException.class, () -> parser.parse(file, "large.txt"));
        assertTrue(error.getMessage().contains("200万"));
    }

    private void addPage(PDDocument document, String text) throws Exception {
        PDPage page = new PDPage();
        document.addPage(page);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(50, 700);
            content.showText(text);
            content.endText();
        }
    }
}
