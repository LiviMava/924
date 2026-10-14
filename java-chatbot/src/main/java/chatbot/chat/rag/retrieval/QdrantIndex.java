package chatbot.chat.rag.retrieval;

import chatbot.chat.rag.config.RagProperties;
import chatbot.chat.rag.domain.ChunkRecord;
import chatbot.chat.rag.model.ModelGateway;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Uses Qdrant's current /points/query API; no dependency on removed legacy Search RPCs. */
@Component
public class QdrantIndex implements VectorIndex {
    private final RagProperties properties;
    private final ModelGateway models;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final String collection;
    private volatile boolean initialized;
    public QdrantIndex(RagProperties properties, ModelGateway models, ObjectMapper json) {
        this.properties = properties; this.models = models; this.json = json;
        String signature = properties.embedding().baseUrl() + "|" + properties.embedding().model() +
            "|" + properties.embedding().dimensions();
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(signature.getBytes(StandardCharsets.UTF_8))).substring(0, 12);
            if (!properties.qdrant().collection().matches("[A-Za-z0-9_-]{1,100}"))
                throw new IllegalArgumentException("Qdrant collection 名称只能包含字母、数字、下划线和连字符");
            collection = properties.qdrant().collection() + "_" + hash;
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public boolean configured() { return models.embeddingConfigured(); }
    public String collection() { return collection; }
    public boolean healthy() {
        try { return request("GET", "/healthz", null).statusCode() == 200; }
        catch (RuntimeException e) { return false; }
    }
    private synchronized void initialize() {
        if (initialized) return;
        var response = request("GET", "/collections/" + collection, null);
        if (response.statusCode() == 404) {
            checked(request("PUT", "/collections/" + collection, Map.of("vectors",
                Map.of("size", properties.embedding().dimensions(), "distance", "Cosine"))));
        } else {
            JsonNode config = checked(response).path("result").path("config").path("params").path("vectors");
            if (config.path("size").asInt() != properties.embedding().dimensions() ||
                !"Cosine".equals(config.path("distance").asText()))
                throw new IllegalStateException("Qdrant 向量维度或距离配置不一致，请使用新的 collection 前缀重建索引");
        }
        initialized = true;
    }
    public void add(List<ChunkRecord> chunks) {
        if (chunks.isEmpty()) return;
        initialize();
        var embeddings = models.embed(chunks.stream().map(ChunkRecord::text).toList());
        addWithEmbeddings(chunks, embeddings);
    }
    /** Reuse already computed vectors; tutorial can expose the exact vectors sent to Qdrant. */
    public void addWithEmbeddings(List<ChunkRecord> chunks, List<float[]> embeddings) {
        if (chunks.isEmpty()) return;
        initialize();
        if (embeddings.size() != chunks.size()) throw new IllegalStateException("Embedding 返回数量不匹配");
        List<Object> points = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            var c = chunks.get(i); float[] vector = embeddings.get(i);
            validate(vector);
            points.add(Map.of("id", c.id(), "vector", vector, "payload",
                Map.of("documentId", c.documentId(), "text", c.text())));
        }
        checked(request("PUT", "/collections/" + collection + "/points?wait=true", Map.of("points", points)));
    }
    public List<Match> search(String query, int limit) {
        return search(query,limit,null);
    }
    public List<Match> search(String query,int limit,String documentId) {
        initialize();
        float[] vector = models.embed(List.of(query)).get(0);
        return searchWithVector(vector,limit,properties.retrieval().minVectorScore(),documentId);
    }
    public List<Match> searchWithVector(float[] vector,int limit,double threshold) {
        return searchWithVector(vector,limit,threshold,null);
    }
    private List<Match> searchWithVector(float[] vector,int limit,double threshold,String documentId) {
        if(limit<1 || limit>100 || !Double.isFinite(threshold) || threshold < -1 || threshold > 1)
            throw new IllegalArgumentException("无效向量查询范围");
        initialize();validate(vector);
        Map<String,Object> body=new HashMap<>(Map.of("query",vector,"limit",limit,"with_payload",false,"score_threshold",threshold));
        if(documentId!=null)body.put("filter",Map.of("must",List.of(Map.of("key","documentId","match",Map.of("value",documentId)))));
        JsonNode hits = checked(request("POST", "/collections/" + collection + "/points/query",body)).path("result").path("points");
        List<Match> result = new ArrayList<>();
        hits.forEach(hit -> result.add(new Match(hit.path("id").asText(), hit.path("score").asDouble())));
        return result;
    }
    public Map<String,Object> statistics() {
        JsonNode result=checked(request("GET","/collections/"+collection,null)).path("result");
        return Map.of("collection",collection,"pointsCount",result.path("points_count").asLong(),
            "dimensions",result.path("config").path("params").path("vectors").path("size").asInt(),"distance","Cosine");
    }
    /** Deliberately limited to isolated tutorial collections, never the company knowledge base. */
    public void deleteTutorialCollection() {
        if(!collection.startsWith("rag_tutorial_")) throw new IllegalStateException("只能清理教学 collection");
        var response=request("DELETE","/collections/"+collection,null);
        if(response.statusCode()!=404) checked(response);
        initialized=false;
    }
    public void deleteDocument(String targetCollection, String documentId) {
        if (targetCollection == null || targetCollection.isBlank()) return;
        if (!targetCollection.matches("[A-Za-z0-9_-]{1,255}")) throw new IllegalArgumentException("无效 collection");
        var result = request("POST", "/collections/" + targetCollection + "/points/delete?wait=true",
            Map.of("filter", Map.of("must", List.of(Map.of("key", "documentId", "match", Map.of("value", documentId))))));
        if (result.statusCode() != 404) checked(result);
    }
    private void validate(float[] vector) {
        if (vector.length != properties.embedding().dimensions())
            throw new IllegalStateException("Embedding 实际维度与 RAG_EMBEDDING_DIMENSIONS 不一致");
        double magnitude = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) throw new IllegalStateException("Embedding 返回非有限数值");
            magnitude += value * (double)value;
        }
        if (magnitude == 0) throw new IllegalStateException("Embedding 返回全零向量");
    }
    private HttpResponse<String> request(String method, String path, Object body) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(properties.qdrant().httpUrl() + path))
                .timeout(Duration.ofSeconds(path.equals("/healthz") ? 3 : 20))
                .header("Content-Type", "application/json");
            if (!properties.qdrant().apiKey().isBlank()) builder.header("api-key", properties.qdrant().apiKey());
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Qdrant 请求已中断", e);
        } catch (Exception e) { throw new IllegalStateException("无法连接 Qdrant，请检查服务配置", e); }
    }
    private JsonNode checked(HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            initialized = false;
            throw new IllegalStateException("Qdrant 请求失败，HTTP " + response.statusCode());
        }
        return json.readTree(response.body());
    }
}
