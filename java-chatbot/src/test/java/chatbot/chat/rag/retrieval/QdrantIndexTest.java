package chatbot.chat.rag.retrieval;

import chatbot.chat.rag.config.RagProperties;
import chatbot.chat.rag.domain.ChunkRecord;
import chatbot.chat.rag.model.ModelGateway;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises real HTTP serialization and routing against an ephemeral, local Qdrant contract stub. */
class QdrantIndexTest {
    private static final String DOCUMENT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String CHUNK_ID = "22222222-2222-4222-8222-222222222222";
    @TempDir Path temp;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String baseUrl;
    private ModelGateway models;
    private volatile boolean collectionExists;
    private volatile int storedDimensions = 3;
    private volatile int queryStatus = 200;
    private volatile boolean deleteNotFound;

    @BeforeEach
    void startLocalServer() throws IOException {
        models = mock(ModelGateway.class);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopLocalServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void createsCollectionThenUpsertsQueriesAndDeletesUsingCurrentHttpContract() {
        QdrantIndex index = index("embed-v1", 3);
        when(models.embed(List.of("差旅住宿限额500元"))).thenReturn(List.of(new float[]{1, .5f, .25f}));
        when(models.embed(List.of("住宿限额多少"))).thenReturn(List.of(new float[]{.5f, 1, .25f}));

        index.add(List.of(chunk()));
        List<VectorIndex.Match> hits = index.search("住宿限额多少", 7);
        index.deleteDocument(index.collection(), DOCUMENT_ID);

        String collectionPath = "/collections/" + index.collection();
        assertEquals(List.of("GET " + collectionPath, "PUT " + collectionPath,
                "PUT " + collectionPath + "/points", "POST " + collectionPath + "/points/query",
                "POST " + collectionPath + "/points/delete"),
                requests.stream().map(request -> request.method() + " " + request.path()).toList());
        assertTrue(requests.stream().allMatch(request -> "qdrant-test-key".equals(request.apiKey())));
        assertEquals(3, requests.get(1).body().path("vectors").path("size").asInt());
        assertEquals("Cosine", requests.get(1).body().path("vectors").path("distance").asText());

        Received upsert = requests.get(2);
        assertEquals("wait=true", upsert.query());
        JsonNode point = upsert.body().path("points").get(0);
        assertEquals(CHUNK_ID, point.path("id").asText());
        assertEquals(DOCUMENT_ID, point.path("payload").path("documentId").asText());
        assertEquals("差旅住宿限额500元", point.path("payload").path("text").asText());
        assertEquals(3, point.path("vector").size());
        assertEquals(.5, point.path("vector").get(1).asDouble(), 1e-6);

        JsonNode query = requests.get(3).body();
        assertEquals(7, query.path("limit").asInt());
        assertEquals(.3, query.path("score_threshold").asDouble(), 1e-6);
        assertFalse(query.path("with_payload").asBoolean());
        assertEquals(3, query.path("query").size());
        assertEquals(List.of(new VectorIndex.Match(CHUNK_ID, .88)), hits);

        Received deletion = requests.get(4);
        assertEquals("wait=true", deletion.query());
        JsonNode condition = deletion.body().path("filter").path("must").get(0);
        assertEquals("documentId", condition.path("key").asText());
        assertEquals(DOCUMENT_ID, condition.path("match").path("value").asText());
        verify(models).embed(List.of("差旅住宿限额500元"));
        verify(models).embed(List.of("住宿限额多少"));
    }

    @Test
    void invalidEmbeddingDimensionFailsBeforeAnyPointIsUpserted() {
        when(models.embed(List.of("差旅住宿限额500元"))).thenReturn(List.of(new float[]{1, .5f}));
        var error = assertThrows(IllegalStateException.class, () -> index("embed-v1", 3).add(List.of(chunk())));

        assertTrue(error.getMessage().contains("维度"));
        assertEquals(2, requests.size(), "Collection initialization is allowed, invalid points must never be sent");
        assertTrue(requests.stream().noneMatch(request -> request.path().contains("/points")));
    }

    @Test
    void filtersByDocumentBeforeVectorTopK() {
        when(models.embed(List.of("allowance"))).thenReturn(List.of(new float[]{1,.5f,.25f}));
        index("embed-v1",3).search("allowance",4,DOCUMENT_ID);
        var query=requests.stream().filter(r->r.path().endsWith("/points/query")).findFirst().orElseThrow().body();
        assertEquals(4,query.path("limit").asInt());
        var condition=query.path("filter").path("must").get(0);
        assertEquals("documentId",condition.path("key").asText());
        assertEquals(DOCUMENT_ID,condition.path("match").path("value").asText());
    }

    @Test
    void mismatchingStoredCollectionSchemaPreventsEmbeddingAndUpsert() {
        collectionExists = true;
        storedDimensions = 8;
        var error = assertThrows(IllegalStateException.class, () -> index("embed-v1", 3).add(List.of(chunk())));

        assertTrue(error.getMessage().contains("维度"));
        assertEquals(1, requests.size());
        verifyNoInteractions(models);
    }

    @Test
    void modelEndpointAndDimensionChangesUseDifferentCollectionsButKeyRotationDoesNot() {
        QdrantIndex original = index("embed-v1", 3);
        QdrantIndex same = index("embed-v1", 3);
        QdrantIndex changedModel = index("embed-v2", 3);
        QdrantIndex changedDimensions = index("embed-v1", 4);
        QdrantIndex changedEndpoint = new QdrantIndex(properties("https://other.invalid/v1", "embedding-key", "embed-v1", 3), models, json);
        QdrantIndex rotatedKey = new QdrantIndex(properties("https://embedding.invalid/v1", "rotated-key", "embed-v1", 3), models, json);

        assertEquals(original.collection(), same.collection());
        assertEquals(original.collection(), rotatedKey.collection());
        assertNotEquals(original.collection(), changedModel.collection());
        assertNotEquals(original.collection(), changedDimensions.collection());
        assertNotEquals(original.collection(), changedEndpoint.collection());
        assertTrue(original.collection().matches("kb_test_[0-9a-f]{12}"));
        assertTrue(requests.isEmpty(), "Constructing an index must not contact the server");
        verifyNoInteractions(models);
    }

    @Test
    void queryFailureClearsInitializationSoTheNextQueryChecksCollectionAgain() {
        collectionExists = true;
        QdrantIndex index = index("embed-v1", 3);
        when(models.embed(List.of("住宿限额"))).thenReturn(List.of(new float[]{1, .5f, .25f}));
        queryStatus = 503;
        assertThrows(IllegalStateException.class, () -> index.search("住宿限额", 4));
        queryStatus = 200;
        assertEquals(1, index.search("住宿限额", 4).size());

        assertEquals(List.of("GET", "POST", "GET", "POST"), requests.stream().map(Received::method).toList());
    }

    @Test
    void deletionOfAnOldMissingCollectionIsIdempotentAndDoesNotInitializeCurrentIndex() {
        deleteNotFound = true;
        QdrantIndex index = index("embed-v2", 3);
        assertDoesNotThrow(() -> index.deleteDocument("kb_old_model", DOCUMENT_ID));
        assertEquals(1, requests.size());
        assertEquals("/collections/kb_old_model/points/delete", requests.get(0).path());
        assertDoesNotThrow(() -> index.deleteDocument(null, DOCUMENT_ID));
        assertDoesNotThrow(() -> index.deleteDocument("", DOCUMENT_ID));
        assertEquals(1, requests.size());
        verifyNoInteractions(models);
    }

    private QdrantIndex index(String model, int dimensions) {
        return new QdrantIndex(properties("https://embedding.invalid/v1", "embedding-key", model, dimensions), models, json);
    }

    private RagProperties properties(String endpoint, String key, String model, int dimensions) {
        return new RagProperties(temp, "service-key",
                new RagProperties.Model("", "", ""),
                new RagProperties.Embedding(endpoint, key, model, dimensions),
                new RagProperties.Qdrant("127.0.0.1", 6334, baseUrl, "qdrant-test-key", false, "kb_test"),
                new RagProperties.Retrieval(30, 5, .3, "", "", ""));
    }

    private ChunkRecord chunk() {
        return new ChunkRecord(CHUNK_ID, DOCUMENT_ID, 0, "差旅住宿限额500元", 2, "差旅规定");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode body = json.readTree(rawBody.isEmpty() ? "{}" : rawBody);
            requests.add(new Received(method, path, exchange.getRequestURI().getRawQuery(), body,
                    exchange.getRequestHeaders().getFirst("api-key")));
            if ("GET".equals(method) && path.matches("/collections/[^/]+")) {
                if (!collectionExists) { respond(exchange, 404, Map.of("status", "not found")); return; }
                respond(exchange, 200, Map.of("result", Map.of("config", Map.of("params",
                        Map.of("vectors", Map.of("size", storedDimensions, "distance", "Cosine"))))));
            } else if ("PUT".equals(method) && path.matches("/collections/[^/]+")) {
                collectionExists = true;
                storedDimensions = body.path("vectors").path("size").asInt();
                respond(exchange, 200, Map.of("result", true, "status", "ok"));
            } else if ("PUT".equals(method) && path.endsWith("/points")) {
                respond(exchange, 200, Map.of("result", Map.of("status", "completed")));
            } else if ("POST".equals(method) && path.endsWith("/points/query")) {
                respond(exchange, queryStatus, Map.of("result", Map.of("points", List.of(Map.of("id", CHUNK_ID, "score", .88)))));
            } else if ("POST".equals(method) && path.endsWith("/points/delete")) {
                respond(exchange, deleteNotFound ? 404 : 200, Map.of("result", Map.of("status", "completed")));
            } else {
                respond(exchange, 404, Map.of("status", "unexpected endpoint"));
            }
        } finally {
            exchange.close();
        }
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private record Received(String method, String path, String query, JsonNode body, String apiKey) { }
}
