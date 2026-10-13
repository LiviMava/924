package chatbot.chat.rag.model;

import chatbot.chat.rag.config.RagProperties;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ModelGatewayTest {
    @Test void actualSpringAiSdkCanEmbedAnswerAndStreamAgainstCompatibleHttpApi() throws Exception {
        var json=new JsonMapper();
        var requests=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1",exchange->{
            try {
                assertEquals("Bearer test-key",exchange.getRequestHeaders().getFirst("Authorization"));
                var body=json.readTree(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                String response;
                if(exchange.getRequestURI().getPath().endsWith("/embeddings")) {
                    assertEquals("test-embedding",body.path("model").asText());
                    response="""
                        {"object":"list","data":[{"object":"embedding","index":0,"embedding":[1.0,0.5,0.25]}],
                        "model":"test-embedding","usage":{"prompt_tokens":3,"total_tokens":3}}
                        """;
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                } else if(body.has("tools")) {
                    assertEquals("test-chat",body.path("model").asText(),"Per-call tool options must preserve configured model");
                    assertEquals("nx_get_context",body.path("tools").get(0).path("function").path("name").asText());
                    response="""
                        {"id":"tool-test","object":"chat.completion","created":1,"model":"test-chat",
                        "choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"nx_get_context","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}
                        """;
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                } else if(body.path("stream").asBoolean()) {
                    response="""
                        data: {"id":"chat-test","object":"chat.completion.chunk","created":1,"model":"test-chat","choices":[{"index":0,"delta":{"role":"assistant","content":"标准为650元。[1]"},"finish_reason":null}]}

                        data: {"id":"chat-test","object":"chat.completion.chunk","created":1,"model":"test-chat","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: [DONE]

                        """;
                    exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                } else {
                    assertEquals("test-chat",body.path("model").asText());
                    response="""
                        {"id":"chat-test","object":"chat.completion","created":1,"model":"test-chat",
                        "choices":[{"index":0,"message":{"role":"assistant","content":"标准为650元。[1]"},"finish_reason":"stop"}],
                        "usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}
                        """;
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                }
                byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,bytes.length);
                exchange.getResponseBody().write(bytes);requests.incrementAndGet();
            } finally { exchange.close(); }
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
            var properties=new RagProperties(Path.of("."),"",
                new RagProperties.Model(base,"test-key","test-chat"),
                new RagProperties.Embedding(base,"test-key","test-embedding",3),
                new RagProperties.Qdrant("127.0.0.1",6334,"http://127.0.0.1:6333","",false,"unused"),
                new RagProperties.Retrieval(30,6,.45,"","",""));
            var gateway=new ModelGateway(properties,ObservationRegistry.NOOP);
            assertArrayEquals(new float[]{1,.5f,.25f},gateway.embed(List.of("费用标准")).get(0));
            assertEquals("标准为650元。[1]",gateway.answer(new Prompt("费用标准")));
            assertEquals("标准为650元。[1]",gateway.tutorialAnswer(new Prompt("重排演示")));
            assertEquals("标准为650元。[1]",gateway.stream(new Prompt("费用标准")).collectList()
                .map(parts->String.join("",parts)).block(Duration.ofSeconds(10)));
            var callback=new org.springframework.ai.tool.ToolCallback() {
                public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
                    return new org.springframework.ai.tool.definition.DefaultToolDefinition("nx_get_context","Read NX", "{\"type\":\"object\",\"properties\":{}}");
                }
                public String call(String input) { fail("SDK must not execute tools implicitly");return ""; }
            };
            assertTrue(gateway.agentTurn(new Prompt("NX状态"),List.of(callback)).getResult().getOutput().hasToolCalls());
            assertEquals(5,requests.get());
        } finally { server.stop(0); }
    }
}
