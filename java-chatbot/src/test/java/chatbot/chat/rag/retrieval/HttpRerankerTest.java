package chatbot.chat.rag.retrieval;

import chatbot.chat.rag.config.RagProperties;
import chatbot.chat.rag.domain.SearchHit;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HttpRerankerTest {
    @Test void mapsProviderIndicesAndScoresAndRejectsMalformedResponses() throws Exception {
        var response=new AtomicReference<>("{\"results\":[{\"index\":1,\"relevance_score\":0.9981234}]}");
        var request=new AtomicReference<String>();var authorization=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/rerank",exchange->{
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var properties=mock(RagProperties.class);
            when(properties.retrieval()).thenReturn(new RagProperties.Retrieval(30,6,.45,"http://127.0.0.1:"+server.getAddress().getPort()+"/rerank","test-token","rerank"));
            var json=new JsonMapper();var ranker=new Reranker(properties,json);
            var hits=List.of(new SearchHit("a","doc","file",1,"","Beijing",.2),new SearchHit("b","doc","file",2,"","Shanghai",.1));
            var result=ranker.rerank("Shanghai",hits,1);
            assertEquals("b",result.get(0).id());assertEquals(.9981234,result.get(0).score(),1e-12);
            assertEquals("Bearer test-token",authorization.get());
            assertEquals("rerank",json.readTree(request.get()).path("model").asText());
            assertEquals(1,json.readTree(request.get()).path("top_n").asInt());
            for(String invalid:List.of("{\"results\":[]}","{\"results\":[{\"index\":0.5,\"relevance_score\":1}]}","{\"results\":[{\"index\":2,\"relevance_score\":1}]}","{\"results\":[{\"index\":0,\"relevance_score\":\"1\"}]}")) {
                response.set(invalid);assertThrows(IllegalStateException.class,()->ranker.rerank("Shanghai",hits,1));
            }
        } finally {server.stop(0);}
    }
}
