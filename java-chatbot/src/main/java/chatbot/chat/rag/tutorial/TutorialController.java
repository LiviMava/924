package chatbot.chat.rag.tutorial;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@RestController
@RequestMapping("/api/v1/tutorial")
@ConditionalOnProperty(name="rag.tutorial.enabled",havingValue="true")
public class TutorialController {
    public record CreateRequest(@Pattern(regexp="md|docx|pdf") @NotNull String sample) {}
    public record ChunkRequest(@Min(100) @Max(1600) int size,@Min(0) @Max(450) int overlap) {}
    public record RetrieveRequest(@NotBlank @Size(max=500) String query,@Min(1) @Max(10) int topK,@DecimalMin("-1") @DecimalMax("1") double threshold,
        @Pattern(regexp="keyword|vector|hybrid") String mode) {
        public RetrieveRequest { if(mode==null)mode="hybrid"; }
        public RetrieveRequest(String query,int topK,double threshold) { this(query,topK,threshold,"hybrid"); }
    }
    public record FusionRequest(@NotBlank @Size(max=500) String query,@Min(1) @Max(24) int topK) {}
    public record RerankRequest(@NotBlank @Size(max=500) String query,@Pattern(regexp="llm|bm25|configured") @NotNull String method,
                              @Pattern(regexp="fused|retrieved|fixture") @NotNull String source) {}
    public record PromptRequest(@NotBlank @Size(max=500) String question,@Min(1) @Max(6) int topK,
                              @Pattern(regexp="ranked|fused|retrieved|fixture") @NotNull String source) {}
    private final TutorialService service;
    public TutorialController(TutorialService service) { this.service=service; }
    private String owner(HttpServletRequest request) { return request.getSession(true).getId(); }
    @GetMapping("/config") public Map<String,Object> config() { return service.config(); }
    @GetMapping("/samples/{sample}") public ResponseEntity<FileSystemResource> sample(@PathVariable String sample) throws java.io.IOException {
        var file=service.sample(sample);
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=\""+file.getFileName()+"\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM).body(new FileSystemResource(file));
    }
    @PostMapping("/runs") public Object create(@Valid @RequestBody CreateRequest body,HttpServletRequest request) throws java.io.IOException {
        return service.create(owner(request),body.sample());
    }
    @PostMapping("/runs/upload") public Object upload(@RequestParam("file") MultipartFile file,HttpServletRequest request) throws java.io.IOException {
        return service.upload(owner(request),file);
    }
    @GetMapping("/runs/{id}") public Object state(@PathVariable String id,HttpServletRequest request) { return service.state(owner(request),id); }
    @PostMapping("/runs/{id}/parse") public Object parse(@PathVariable String id,HttpServletRequest request) { return service.parse(owner(request),id); }
    @PostMapping("/runs/{id}/chunk") public Object chunk(@PathVariable String id,@Valid @RequestBody ChunkRequest body,HttpServletRequest request) { return service.chunk(owner(request),id,body); }
    @PostMapping("/runs/{id}/embed") public Object embed(@PathVariable String id,HttpServletRequest request) { return service.embed(owner(request),id); }
    @PostMapping("/runs/{id}/retrieve") public Object retrieve(@PathVariable String id,@Valid @RequestBody RetrieveRequest body,HttpServletRequest request) { return service.retrieve(owner(request),id,body); }
    @PostMapping("/runs/{id}/fusion") public Object fusion(@PathVariable String id,@Valid @RequestBody FusionRequest body,HttpServletRequest request) { return service.fusion(owner(request),id,body); }
    @PostMapping("/runs/{id}/rerank") public Object rerank(@PathVariable String id,@Valid @RequestBody RerankRequest body,HttpServletRequest request) { return service.rerank(owner(request),id,body); }
    @PostMapping("/runs/{id}/prompt") public Object prompt(@PathVariable String id,@Valid @RequestBody PromptRequest body,HttpServletRequest request) { return service.prompt(owner(request),id,body); }
    @PostMapping("/runs/{id}/generate") public Object generate(@PathVariable String id,HttpServletRequest request) { return service.generate(owner(request),id); }
    @DeleteMapping("/runs/{id}") public Object delete(@PathVariable String id,HttpServletRequest request) { return service.delete(owner(request),id); }
}
