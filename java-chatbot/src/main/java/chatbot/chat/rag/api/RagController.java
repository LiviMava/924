package chatbot.chat.rag.api;

import chatbot.chat.rag.api.ApiModels.*;
import chatbot.chat.rag.config.RagProperties;
import chatbot.chat.rag.domain.DocumentRecord;
import chatbot.chat.rag.model.ModelGateway;
import chatbot.chat.rag.retrieval.*;
import chatbot.chat.rag.service.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/api/v1")
@Validated
public class RagController {
    private final DocumentService documents;
    private final HybridRetriever retriever;
    private final RagService rag;
    private final ModelGateway models;
    private final VectorIndex vectors;
    private final RagProperties properties;
    private final ObjectMapper json;
    private final chatbot.chat.rag.mcp.NxMcpClient nxMcp;
    private final Semaphore chatSlots=new Semaphore(8);
    public RagController(DocumentService documents,HybridRetriever retriever,RagService rag,ModelGateway models,
                         VectorIndex vectors,RagProperties properties,ObjectMapper json,chatbot.chat.rag.mcp.NxMcpClient nxMcp) {
        this.documents=documents;this.retriever=retriever;this.rag=rag;this.models=models;
        this.vectors=vectors;this.properties=properties;this.json=json;
        this.nxMcp=nxMcp;
    }
    @GetMapping("/system") public Map<String,Object> system() {
        return Map.of("name","Company Knowledge","chatConfigured",models.chatConfigured(),
            "embeddingConfigured",models.embeddingConfigured(),"vectorHealthy",vectors.healthy(),
            "authenticationRequired",!properties.apiKey().isBlank(),"supportedFormats",List.of("pdf","doc","docx","txt","md"),
            "maxUploadBytes",DocumentService.MAX_BYTES,"mcpConfigured",nxMcp.configured());
    }
    @GetMapping("/documents") public List<DocumentRecord> list(@RequestParam(defaultValue="100") @Min(1) @Max(500) int limit,
                                                              @RequestParam(defaultValue="0") @Min(0) int offset) {
        return documents.list(limit,offset);
    }
    @GetMapping("/documents/{id}") public DocumentRecord get(@PathVariable String id) { return documents.get(id); }
    @PostMapping(value="/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentRecord> upload(@RequestPart MultipartFile file) throws IOException {
        var document=documents.upload(file);
        return ResponseEntity.accepted().header("Location","/api/v1/documents/"+document.id()).body(document);
    }
    @PostMapping("/documents/{id}/retry") public ResponseEntity<DocumentRecord> retry(@PathVariable String id) {
        return ResponseEntity.accepted().body(documents.retry(id));
    }
    @DeleteMapping("/documents/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { documents.delete(id); }
    @GetMapping("/documents/{id}/content") public ResponseEntity<FileSystemResource> content(@PathVariable String id) {
        var document=documents.get(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(document.filename(),StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"no-store")
            .body(new FileSystemResource(documents.content(id)));
    }
    @PostMapping("/search") public HybridRetriever.Result search(@Valid @RequestBody SearchRequest request) {
        return retriever.search(request.query(),request.topK()==null ? properties.retrieval().topK() : request.topK(),request.documentId());
    }
    @PostMapping("/chat") public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        acquire();
        try { return rag.answer(request); } finally { chatSlots.release(); }
    }
    @PostMapping(value="/chat/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public void stream(@Valid @RequestBody ChatRequest request,HttpServletResponse response) throws IOException {
        acquire();
        try {
            var prepared=rag.prepare(request); // Report configuration/validation failures as real non-2xx JSON.
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setHeader("Cache-Control","no-store");response.setHeader("X-Accel-Buffering","no");
            PrintWriter output=response.getWriter();
            send(output,"sources",Map.of("citations",prepared.retrieval().hits(),
                "retrievalMode",prepared.retrieval().retrievalMode(),"warnings",prepared.retrieval().warnings()));
            try {
                StringBuilder answer=new StringBuilder();
                if (prepared.prompt()!=null) {
                    // Bounded stream; client disconnect cancels upstream subscription via closeable stream.
                    var deadline=reactor.core.publisher.Mono.delay(java.time.Duration.ofSeconds(120))
                        .flatMap(ignored -> reactor.core.publisher.Mono.error(new IllegalStateException("回答超过总时限")));
                    try (var pieces=models.stream(prepared.prompt()).takeUntilOther(deadline).toStream(1)) {
                        var iterator=pieces.iterator();
                        while(iterator.hasNext()) {
                            String text=iterator.next();
                            if(answer.length()+text.length()>32000) throw new IllegalStateException("回答超过长度限制");
                            answer.append(text);send(output,"delta",Map.of("text",text));
                        }
                    }
                } else answer.append(RagService.NO_EVIDENCE);
                send(output,"done",rag.finish(prepared,answer.toString()));
            } catch (Exception e) {
                if (!output.checkError()) send(output,"error",Map.of("message","回答未完成，请检查模型服务配置或稍后重试。"));
            }
        } finally { chatSlots.release(); }
    }
    private void acquire() {
        if(!chatSlots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"当前问答请求较多，请稍后重试");
    }
    private void send(PrintWriter output,String event,Object body) throws IOException {
        output.write("event: "+event+"\ndata: "+json.writeValueAsString(body)+"\n\n");output.flush();
        if(output.checkError()) throw new IOException("客户端已断开");
    }
}
