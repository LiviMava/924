package chatbot.chat.rag.tutorial;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class TutorialApiIntegrationTest {
    @TempDir static Path data;
    @DynamicPropertySource static void props(DynamicPropertyRegistry p) {
        p.add("rag.data-dir",()->data.toString());p.add("spring.datasource.url",()->"jdbc:h2:mem:tutorial_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        p.add("rag.api-key",()->"tutorial-test-token");p.add("rag.tutorial.enabled",()->"true");
        p.add("rag.tutorial.corpus-dir",()->data.resolve("handbooks").toString());
        p.add("rag.worker-initial-delay-ms",()->"3600000");
    }
    @Value("${local.server.port}") int port;
    @MockitoBean TutorialService service;
    HttpResponse<String> call(String method,String path,String body,boolean authenticated,String origin) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(authenticated)request.header("Authorization","Bearer tutorial-test-token");if(origin!=null)request.header("Origin",origin);
        request.header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void staticLessonIsPublicButExperimentDataRequiresAuthentication() throws Exception {
        assertEquals(200,call("GET","/tutorial.html",null,false,null).statusCode());
        assertEquals(200,call("GET","/tutorial.js",null,false,null).statusCode());
        assertEquals(401,call("GET","/api/v1/tutorial/config",null,false,null).statusCode());
        assertEquals(401,call("GET","/api/v1/tutorial/handbooks",null,false,null).statusCode());
        assertEquals(200,call("GET","/api/v1/tutorial/handbooks",null,true,null).statusCode());
        when(service.config()).thenReturn(Map.of("embeddingConfigured",false));
        assertEquals(200,call("GET","/api/v1/tutorial/config",null,true,null).statusCode());
    }
    @Test void crossSiteWritesAndOutOfRangeParametersAreRejectedBeforeCallingService() throws Exception {
        assertEquals(403,call("POST","/api/v1/tutorial/runs","{\"sample\":\"md\"}",true,"https://attacker.invalid").statusCode());
        assertEquals(400,call("POST","/api/v1/tutorial/runs","{\"sample\":\"../../secret\"}",true,null).statusCode());
        assertEquals(400,call("POST","/api/v1/tutorial/runs/id/chunk","{\"size\":20,\"overlap\":0}",true,null).statusCode());
        assertEquals(400,call("POST","/api/v1/tutorial/runs/id/retrieve","{\"query\":\"test\",\"topK\":1000,\"threshold\":0}",true,null).statusCode());
        assertEquals(400,call("POST","/api/v1/tutorial/runs/id/fusion","{\"query\":\"test\",\"topK\":25}",true,null).statusCode());
        assertEquals(401,call("POST","/api/v1/tutorial/runs/id/fusion","{\"query\":\"test\",\"topK\":5}",false,null).statusCode());
        assertEquals(400,call("POST","/api/v1/tutorial/runs/from-document","{\"documentId\":\"../../file\",\"fromPage\":1,\"toPage\":2}",true,null).statusCode());
        verifyNoInteractions(service);
    }
}
