package chatbot.chat.rag.retrieval;

import chatbot.chat.rag.config.RagProperties;
import chatbot.chat.rag.domain.SearchHit;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Component
public class Reranker {
    private final RagProperties.Retrieval config;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public Reranker(RagProperties properties, ObjectMapper json) { this.config=properties.retrieval(); this.json=json; }
    public boolean configured() { return !config.rerankUrl().isBlank() && !config.rerankModel().isBlank(); }
    /** Cohere-compatible rerank HTTP contract, also supported by multiple inference gateways. */
    public List<SearchHit> rerank(String query, List<SearchHit> hits, int topK) {
        try {
            var body = Map.of("model", config.rerankModel(), "query", query,
                "documents", hits.stream().map(SearchHit::text).toList(), "top_n", Math.min(topK,hits.size()));
            var request = HttpRequest.newBuilder(URI.create(config.rerankUrl())).timeout(Duration.ofSeconds(15))
                .header("Content-Type","application/json");
            if (!config.rerankApiKey().isBlank()) request.header("Authorization","Bearer "+config.rerankApiKey());
            var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()/100 != 2) throw new IllegalStateException("重排服务暂时不可用");
            var items = json.readTree(response.body()).path("results");
            List<SearchHit> result = new ArrayList<>(); Set<Integer> seen = new HashSet<>();
            for (var item : items) {
                int index = item.path("index").asInt(-1);
                double score = item.path("relevance_score").asDouble(Double.NaN);
                if (index < 0 || index >= hits.size() || !Double.isFinite(score) || !seen.add(index))
                    throw new IllegalStateException("重排响应格式不正确");
                result.add(hits.get(index).withScore(score));
            }
            if (result.isEmpty()) throw new IllegalStateException("重排服务返回空结果");
            return result.stream().limit(topK).toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("重排请求已中断",e);
        } catch (Exception e) { throw new IllegalStateException("重排服务暂时不可用", e); }
    }
}
