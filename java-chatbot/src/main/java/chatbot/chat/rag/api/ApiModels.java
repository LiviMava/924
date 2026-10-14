package chatbot.chat.rag.api;

import chatbot.chat.rag.domain.SearchHit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;

public final class ApiModels {
    private ApiModels() {}
    public record SearchRequest(@NotBlank @Size(max=2000) String query, @Min(1) @Max(12) Integer topK,
                                @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String documentId) {
        public SearchRequest(String query,Integer topK) { this(query,topK,null); }
    }
    public record HistoryMessage(@Pattern(regexp="user|assistant") @NotNull String role,
                                 @NotBlank @Size(max=8000) String content) {}
    public record ChatRequest(@NotBlank @Size(max=2000) String question,
                              @Size(max=20) List<@NotNull @Valid HistoryMessage> history,
                              @Pattern(regexp="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String documentId) {
        public ChatRequest(String question,List<HistoryMessage> history) { this(question,history,null); }
    }
    public record ChatResponse(String answer, List<SearchHit> citations, boolean grounded,
                               String retrievalMode, List<String> warnings) {}
}
