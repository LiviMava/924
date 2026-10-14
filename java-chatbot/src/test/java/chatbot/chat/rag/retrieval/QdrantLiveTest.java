package chatbot.chat.rag.retrieval;

import chatbot.chat.rag.config.RagProperties;
import chatbot.chat.rag.domain.ChunkRecord;
import chatbot.chat.rag.model.ModelGateway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="RAG_TEST_QDRANT_URL",matches=".+")
class QdrantLiveTest {
    @Test void realDatabaseRoundTrip() throws Exception {
        String url=System.getenv("RAG_TEST_QDRANT_URL");
        String prefix="rag_smoke_"+UUID.randomUUID().toString().replace("-","");
        var model=mock(ModelGateway.class);
        when(model.embeddingConfigured()).thenReturn(true);
        when(model.embed(anyList())).thenAnswer(invocation->{
            List<String> texts=invocation.getArgument(0);
            return texts.stream().map(text->new float[]{1,.2f,.1f}).toList();
        });
        var properties=new RagProperties(Path.of("."),"",new RagProperties.Model("","",""),
            new RagProperties.Embedding("http://test.invalid","unused","test-embedding",3),
            new RagProperties.Qdrant("127.0.0.1",6334,url,"",false,prefix),
            new RagProperties.Retrieval(30,6,0,"","",""));
        var index=new QdrantIndex(properties,model,new JsonMapper());
        String doc=UUID.randomUUID().toString(), chunk=UUID.randomUUID().toString();
        try {
            assertTrue(index.healthy());
            index.add(List.of(new ChunkRecord(chunk,doc,0,"仅用于数据库集成测试的虚构制度",1,"test")));
            var found=index.search("制度",5);
            assertEquals(1,found.size());assertEquals(chunk,found.get(0).id());
            assertEquals(1,index.search("制度",5,doc).size());
            assertTrue(index.search("制度",5,UUID.randomUUID().toString()).isEmpty(),"Document filter must run before Top-K");
            index.deleteDocument(index.collection(),doc);
            assertTrue(index.search("制度",5).isEmpty());
        } finally {
            assertTrue(index.collection().startsWith(prefix+"_"));
            var request=HttpRequest.newBuilder(URI.create(url+"/collections/"+index.collection()))
                .timeout(Duration.ofSeconds(10)).DELETE().build();
            var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());
            assertTrue(response.statusCode()==200 || response.statusCode()==404,response.body());
        }
    }
}
