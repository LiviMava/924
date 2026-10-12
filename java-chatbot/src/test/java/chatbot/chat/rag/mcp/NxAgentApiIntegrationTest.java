package chatbot.chat.rag.mcp;

import chatbot.chat.rag.model.ModelGateway;
import chatbot.chat.rag.retrieval.VectorIndex;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class NxAgentApiIntegrationTest {
    @TempDir static Path data;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("rag.data-dir",()->data.toString());p.add("spring.servlet.multipart.location",()->data.resolve("tmp").toString());
        p.add("spring.datasource.url",()->"jdbc:h2:mem:nx_agent_api;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        p.add("rag.api-key",()->"agent-test-token");p.add("rag.worker-initial-delay-ms",()->"3600000");
    }
    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired ToolActionService actions;
    @MockitoBean NxMcpClient mcp;
    @MockitoBean ModelGateway model;
    @MockitoBean VectorIndex vectors;
    private HttpClient http;
    @BeforeEach void defaults() {
        http=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
        when(mcp.configured()).thenReturn(true);when(model.chatConfigured()).thenReturn(true);
        when(vectors.configured()).thenReturn(false);
        when(mcp.tools()).thenReturn(List.of(McpSchema.Tool.builder("nx_get_context",Map.of("type","object","properties",Map.of())).build(),
            McpSchema.Tool.builder("nx_preview_block",Map.of("type","object","properties",Map.of())).build()));
        when(mcp.read(eq("nx_get_context"),anyMap())).thenReturn(Map.of("backend","MOCK","hasWorkPart",true,"snapshot","s1"));
        when(mcp.read(eq("nx_preview_block"),anyMap())).thenReturn(Map.of("planId",UUID.randomUUID().toString(),"requiresApproval",true,
            "expiresAtEpochMs",System.currentTimeMillis()+300000,"parameters",Map.of("length",100,"width",50,"height",20)));
        when(model.agentTurn(any(),anyList(),any())).thenReturn(turn("nx_get_context","{}"),turn("nx_preview_block","{\"x\":0,\"y\":0,\"z\":0,\"length\":100,\"width\":50,\"height\":20}"));
        doAnswer(invocation->Map.of("status","SUCCEEDED","backend","MOCK","commandId",((Map<?,?>)invocation.getArgument(0)).get("commandId"))).when(mcp).applyApproved(anyMap());
    }
    @Test void planRequiresOwnerAndBoundConfirmationAndNeverAutoExecutes() throws Exception {
        var plan=plan();String id=plan.path("id").asText(),hash=plan.path("argumentsHash").asText();
        verify(mcp,never()).applyApproved(anyMap());
        assertEquals(404,call(HttpClient.newHttpClient(),"GET","/actions/"+id,null).statusCode(),"approval is bound to session cookie");
        assertEquals(409,call(http,"POST","/actions/"+id+"/approve","{\"argumentsHash\":\"tampered\"}").statusCode());
        var approved=call(http,"POST","/actions/"+id+"/approve",json.writeValueAsString(Map.of("argumentsHash",hash)));
        assertEquals("SUCCEEDED",json.readTree(approved.body()).path("status").asText(),approved.body());
        assertEquals(200,call(http,"POST","/actions/"+id+"/approve",json.writeValueAsString(Map.of("argumentsHash",hash))).statusCode());
        verify(mcp,times(1)).applyApproved(anyMap());
    }
    @Test void cancelAndExpiryDoNotExecute() throws Exception {
        var plan=plan();String id=plan.path("id").asText();
        assertEquals("CANCELLED",json.readTree(call(http,"POST","/actions/"+id+"/cancel","{}").body()).path("status").asText());
        call(http,"POST","/actions/"+id+"/approve",json.writeValueAsString(Map.of("argumentsHash",plan.path("argumentsHash").asText())));
        verify(mcp,never()).applyApproved(anyMap());
        defaults();var expired=plan();String expiredId=expired.path("id").asText();
        db.update("UPDATE tool_actions SET expires_at=? WHERE id=?",OffsetDateTime.now().minusMinutes(1),expiredId);
        assertEquals("EXPIRED",json.readTree(call(http,"POST","/actions/"+expiredId+"/approve",json.writeValueAsString(Map.of("argumentsHash",expired.path("argumentsHash").asText()))).body()).path("status").asText());
        verify(mcp,never()).applyApproved(anyMap());
    }
    @Test void timeoutBecomesUnknownAndReconciliationNeverReplays() throws Exception {
        doThrow(new IllegalStateException("timeout")).when(mcp).applyApproved(anyMap());
        var plan=plan();String id=plan.path("id").asText();
        var approved=call(http,"POST","/actions/"+id+"/approve",json.writeValueAsString(Map.of("argumentsHash",plan.path("argumentsHash").asText())));
        assertEquals("UNKNOWN",json.readTree(approved.body()).path("status").asText());
        call(http,"POST","/actions/"+id+"/approve",json.writeValueAsString(Map.of("argumentsHash",plan.path("argumentsHash").asText())));
        when(mcp.read(eq("nx_command_status"),anyMap())).thenReturn(Map.of("status","SUCCEEDED","backend","MOCK"));
        assertEquals("SUCCEEDED",json.readTree(call(http,"POST","/actions/"+id+"/reconcile","{}").body()).path("status").asText());
        verify(mcp,times(1)).applyApproved(anyMap());
    }
    @Test void maliciousWriteToolIsRejectedEvenWhenModelRequestsIt() throws Exception {
        when(model.agentTurn(any(),anyList(),any())).thenReturn(turn("nx_apply_block","{}"));
        assertEquals(503,call(http,"POST","/chat","{\"question\":\"直接写入\"}").statusCode());
        verify(mcp,never()).applyApproved(anyMap());verify(mcp,never()).read(anyString(),anyMap());
    }
    @Test void streamEmitsToolsAndApprovalBeforeDoneWithoutWriting() throws Exception {
        var response=call(http,"POST","/chat/stream","{\"question\":\"在原点创建100x50x20毫米长方体\"}");
        assertEquals(200,response.statusCode());assertTrue(response.body().contains("event: tool"));
        assertTrue(response.body().contains("event: approval"));assertTrue(response.body().contains("event: done"));
        verify(mcp,never()).applyApproved(anyMap());
    }
    @Test void simultaneousConfirmationsClaimOnceAndRestartMarksUnknown() throws Exception {
        var plan=plan();String id=plan.path("id").asText();String body=json.writeValueAsString(Map.of("argumentsHash",plan.path("argumentsHash").asText()));
        doAnswer(invocation->{ Thread.sleep(150);return Map.of("status","SUCCEEDED","backend","MOCK"); }).when(mcp).applyApproved(anyMap());
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/agent/actions/"+id+"/approve"))
            .header("Authorization","Bearer agent-test-token").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var first=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());var second=http.sendAsync(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,first.get().statusCode());assertEquals(200,second.get().statusCode());verify(mcp,times(1)).applyApproved(anyMap());
        db.update("UPDATE tool_actions SET status='EXECUTING' WHERE id=?",id);actions.recover();
        assertEquals("UNKNOWN",json.readTree(call(http,"GET","/actions/"+id,null).body()).path("status").asText());
    }
    private tools.jackson.databind.JsonNode plan() throws Exception {
        var response=call(http,"POST","/chat","{\"question\":\"在原点创建100x50x20毫米长方体\"}");
        assertEquals(200,response.statusCode(),response.body());return json.readTree(response.body()).path("pendingAction");
    }
    private HttpResponse<String> call(HttpClient client,String method,String path,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/agent"+path)).header("Authorization","Bearer agent-test-token").header("Content-Type","application/json")
            .method(method,body==null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    private static ChatResponse turn(String name,String args) {
        var message=AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall(UUID.randomUUID().toString(),"function",name,args))).build();
        return new ChatResponse(List.of(new Generation(message)));
    }
}
