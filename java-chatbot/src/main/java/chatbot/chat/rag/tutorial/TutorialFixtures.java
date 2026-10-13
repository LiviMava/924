package chatbot.chat.rag.tutorial;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.core.io.ClassPathResource;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Generated binaries are project-local; the authored Markdown is the checked-in fixture. */
public final class TutorialFixtures {
    private TutorialFixtures() {}
    public static Path create(Path root,String kind) throws IOException {
        if(!Set.of("md","docx","pdf").contains(kind)) throw new IllegalArgumentException("请选择 md、docx 或 pdf 示例");
        Files.createDirectories(root);
        Path path=root.resolve("demo-handbook."+kind);
        if(Files.exists(path)) return path;
        String text=new ClassPathResource("tutorial/company-travel.md").getContentAsString(StandardCharsets.UTF_8);
        if(kind.equals("md")) Files.writeString(path,text,StandardCharsets.UTF_8);
        if(kind.equals("docx")) {
            try(var doc=new XWPFDocument();var out=Files.newOutputStream(path)) {
                for(String line:text.split("\n")) {
                    if(line.isBlank()) continue;
                    var paragraph=doc.createParagraph();
                    if(line.startsWith("#")) paragraph.setStyle("Heading1");
                    paragraph.createRun().setText(line.replaceFirst("^#+\\s*",""));
                }
                var h=doc.createParagraph();h.setStyle("Heading1");h.createRun().setText("限额汇总表");
                var table=doc.createTable(4,2);
                String[][] cells={{"目的地","住宿上限（元/人/晚）"},{"上海","650"},{"北京","550"},{"其他城市","450"}};
                for(int i=0;i<cells.length;i++) for(int j=0;j<2;j++) table.getRow(i).getCell(j).setText(cells[i][j]);
                doc.write(out);
            }
        }
        if(kind.equals("pdf")) {
            try(var doc=new PDDocument()) {
                addPage(doc,List.of("Fictional company handbook - teaching fixture", "Page 1: travel accommodation", "Shanghai: hotel reimbursement up to CNY 650 per person per night.",
                    "Beijing: up to CNY 550 per person per night.","Other cities: up to CNY 450 per person per night.","Meals and transport are separate expenses.","Amounts above the cap need prior written approval."));
                addPage(doc,List.of("Page 2: reimbursement procedure", "Submit within 15 working days after the trip.","Required: hotel invoice, travel approval and expense details.","Manager reviews the trip; finance checks invoices and limits.","Shanghai office meeting meals: CNY 80 per person.","This meeting budget is not the hotel reimbursement cap."));
                doc.save(path.toFile());
            }
        }
        return path;
    }
    private static void addPage(PDDocument doc,List<String> lines) throws IOException {
        var page=new PDPage();doc.addPage(page);
        try(var content=new PDPageContentStream(doc,page)) {
            content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),11);
            content.setLeading(22);content.newLineAtOffset(45,735);
            for(String line:lines) { content.showText(line);content.newLine(); }
            content.endText();
        }
    }
}
