package chatbot.chat.rag.tutorial;

import chatbot.chat.rag.service.DocumentService;
import chatbot.chat.rag.store.DocumentRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/** Read-only corpus inventory; downloads/imports/evaluation are explicit, reproducible CLI operations. */
@RestController
@RequestMapping("/api/v1/tutorial")
@ConditionalOnProperty(name="rag.tutorial.enabled",havingValue="true")
public class HandbookCorpusController {
    public record PageRequest(@NotBlank @Pattern(regexp="[0-9a-f-]{36}") String documentId,
                              @Min(1) @Max(500) int fromPage,@Min(1) @Max(500) int toPage) {}
    private final Path root;
    private final DocumentRepository repository;
    private final DocumentService documents;
    private final TutorialService tutorial;
    private final ObjectMapper json;
    public HandbookCorpusController(@Value("${rag.tutorial.corpus-dir:./.runtime/handbooks}") Path root,
            DocumentRepository repository,DocumentService documents,TutorialService tutorial,ObjectMapper json) {
        this.root=root;this.repository=repository;this.documents=documents;this.tutorial=tutorial;this.json=json;
    }
    @GetMapping("/handbooks") public Object catalog() throws java.io.IOException {
        List<Object> items=new ArrayList<>();int ready=0;long chunks=0;
        Path manifest=root.resolve("manifest.json");
        if(Files.isRegularFile(manifest))for(var entry:json.readTree(Files.readString(manifest))) {
            if(!entry.path("status").asText().equals("DOWNLOADED"))continue;
            var doc=repository.byHash(entry.path("sha256").asText());
            Map<String,Object> item=new LinkedHashMap<>();
            for(String field:List.of("title","sourceUrl","downloadUrl","filename","sha256","publisherHost","fetchedAt"))item.put(field,entry.path(field).asText());
            item.put("pages",entry.path("pages").asInt());item.put("bytes",entry.path("bytes").asLong());
            item.put("status",doc.map(d->d.status()).orElse("NOT_IMPORTED"));
            item.put("documentId",doc.map(d->d.id()).orElse(""));
            item.put("chunkCount",doc.map(d->d.chunkCount()).orElse(0));
            if(doc.isPresent() && doc.get().status().equals("READY")){ready++;chunks+=doc.get().chunkCount();}
            items.add(item);
        }
        Path questions=root.resolve("questions.json");
        Object cases=Files.isRegularFile(questions)?json.readTree(Files.readString(questions)):List.of();
        Path report=root.resolve("evaluation-latest.json");
        Object evaluation=Files.isRegularFile(report)?json.readTree(Files.readString(report)):Map.of();
        return Map.of("items",items,"downloaded",items.size(),"ready",ready,"chunks",chunks,"questions",cases,"evaluation",evaluation);
    }
    @PostMapping("/runs/from-document") public Object pages(@Valid @RequestBody PageRequest body,HttpServletRequest request) throws java.io.IOException {
        var doc=documents.get(body.documentId());
        if(!doc.status().equals("READY"))throw new IllegalArgumentException("文档尚未完成入库");
        return tutorial.fromDocument(request.getSession(true).getId(),documents.content(doc.id()),doc.filename(),body.fromPage(),body.toPage());
    }
}
