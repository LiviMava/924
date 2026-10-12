package company.rag.nx;

import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"nx.backend=mock","nx.mcp-api-key=integration-mcp-token-long-enough"})
class NxMcpIntegrationTest {
    @Value("${local.server.port}") int port;
    private static final String TOKEN="integration-mcp-token-long-enough";
    @Test void realStreamableMcpDiscoveryPreviewApprovalAndDeduplication() {
        var transport=HttpClientStreamableHttpTransport.builder("http://127.0.0.1:"+port).endpoint("/mcp")
            .httpRequestCustomizer((builder,method,uri,body,context)->builder.header("Authorization","Bearer "+TOKEN))
            .openConnectionOnStartup(false).build();
        try(var client=McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build()) {
            assertNotNull(client.initialize());
            assertEquals(4,client.listTools().tools().size());
            assertFalse(client.listTools().tools().stream().filter(t->t.name().equals("nx_apply_block")).findFirst().orElseThrow().annotations().readOnlyHint());
            var before=body(call(client,"nx_get_context",Map.of()));assertEquals("MOCK",before.get("backend"));
            var dims=Map.<String,Object>of("x",0,"y",0,"z",0,"length",100,"width",50,"height",20);
            var plan=body(call(client,"nx_preview_block",dims));
            assertEquals(before.get("snapshot"),body(call(client,"nx_get_context",Map.of())).get("snapshot"),"preview does not mutate");
            String command=UUID.randomUUID().toString();
            var apply=Map.<String,Object>of("planId",plan.get("planId"),"commandId",command);
            var first=body(call(client,"nx_apply_block",apply));assertEquals("SUCCEEDED",first.get("status"));
            assertEquals(first,body(call(client,"nx_apply_block",apply)),"same command receipt is reused");
            assertTrue(call(client,"nx_apply_block",Map.of("planId",plan.get("planId"),"commandId",UUID.randomUUID().toString())).isError());
            assertEquals(first,body(call(client,"nx_command_status",Map.of("commandId",command))));
            var stale=body(call(client,"nx_preview_block",dims));
            var other=body(call(client,"nx_preview_block",dims));
            call(client,"nx_apply_block",Map.of("planId",other.get("planId"),"commandId",UUID.randomUUID().toString()));
            assertTrue(call(client,"nx_apply_block",Map.of("planId",stale.get("planId"),"commandId",UUID.randomUUID().toString())).isError());
            var bad=new HashMap<>(dims);bad.put("length",-10);
            assertTrue(call(client,"nx_preview_block",bad).isError());
        }
    }
    @Test void tokenAndOriginRequired() throws Exception {
        var http=HttpClient.newHttpClient();
        var unauth=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/mcp")).GET().build();
        assertEquals(401,http.send(unauth,HttpResponse.BodyHandlers.ofString()).statusCode());
        var cross=HttpRequest.newBuilder(unauth.uri()).header("Authorization","Bearer "+TOKEN).header("Origin","https://evil.example").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(403,http.send(cross,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    private static McpSchema.CallToolResult call(McpSyncClient client,String name,Map<String,Object> args) {
        return client.callTool(McpSchema.CallToolRequest.builder(name).arguments(args).build());
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> body(McpSchema.CallToolResult r) {
        assertFalse(Boolean.TRUE.equals(r.isError()),r.toString());return (Map<String,Object>)r.structuredContent();
    }
}
