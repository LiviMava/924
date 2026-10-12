package chatbot.chat.rag.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

@Component
public class NxMcpClient {
    public static final Set<String> READ_TOOLS=Set.of("nx_get_context","nx_preview_block","nx_command_status");
    public static final String WRITE_TOOL="nx_apply_block";
    private final McpProperties properties;
    private final ObjectMapper json;
    private McpSyncClient client;
    public NxMcpClient(McpProperties properties,ObjectMapper json) {
        this.properties=properties;this.json=json;
        if(properties.enabled()) {
            URI uri=URI.create(properties.url());
            if(uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null || uri.getHost()==null)
                throw new IllegalArgumentException("MCP URL must be a configured endpoint without credentials or query");
            if(!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && Set.of("127.0.0.1","localhost","[::1]").contains(uri.getHost())))
                throw new IllegalArgumentException("Remote MCP requires HTTPS");
            if(properties.apiKey()==null || properties.apiKey().length()<24 || properties.timeoutSeconds()<1 || properties.timeoutSeconds()>60)
                throw new IllegalArgumentException("MCP token or timeout invalid");
        }
    }
    public boolean configured() { return properties.enabled(); }
    private synchronized McpSyncClient connect() {
        if(!configured()) throw new IllegalStateException("NX MCP 未启用，请配置并启动项目内 MCP Server");
        if(client==null) {
            URI endpoint=URI.create(properties.url());
            String base=endpoint.getScheme()+"://"+endpoint.getRawAuthority();
            var transport=HttpClientStreamableHttpTransport.builder(base).endpoint(endpoint.getRawPath())
                .clientBuilder(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER))
                .httpRequestCustomizer((builder,method,uri,body,context)->builder.header("Authorization","Bearer "+properties.apiKey()))
                .maxResponseSize(65536).openConnectionOnStartup(false).build();
            var candidate=McpClient.sync(transport).requestTimeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .clientInfo(new McpSchema.Implementation("company-rag-host","1.0.0")).build();
            try { candidate.initialize();client=candidate; }
            catch(Exception e) { candidate.close();throw new IllegalStateException("无法连接 NX MCP Server，请检查启动状态和独立服务令牌",e); }
        }
        return client;
    }
    public List<McpSchema.Tool> tools() {
        var connection=connect();List<McpSchema.Tool> result=new ArrayList<>();String cursor=null;
        for(int page=0;page<10;page++) {
            var batch=connection.listTools(cursor);
            result.addAll(batch.tools().stream().filter(t->READ_TOOLS.contains(t.name()) || WRITE_TOOL.equals(t.name())).toList());
            cursor=batch.nextCursor();if(cursor==null) return result;
        }
        throw new IllegalStateException("MCP tool list exceeded bound");
    }
    public Map<String,Object> read(String name,Map<String,Object> arguments) {
        if(!READ_TOOLS.contains(name)) throw new IllegalArgumentException("模型不能直接执行写入工具");
        return call(name,arguments);
    }
    // Accessible only through the durable action service after its approval CAS succeeds.
    Map<String,Object> applyApproved(Map<String,Object> arguments) { return call(WRITE_TOOL,arguments); }
    private Map<String,Object> call(String name,Map<String,Object> arguments) {
        var result=connect().callTool(McpSchema.CallToolRequest.builder(name).arguments(arguments).build());
        Object structured=result.structuredContent();
        if(Boolean.TRUE.equals(result.isError()) && !(structured instanceof Map<?,?> body && "FAILED".equals(body.get("status"))))
            throw new IllegalStateException("NX 工具返回失败；写入结果需按 commandId 核查，不要重复提交");
        if(structured==null) {
            var text=result.content().stream().filter(c->c instanceof McpSchema.TextContent)
                .map(c->((McpSchema.TextContent)c).text()).findFirst().orElseThrow();
            structured=json.readValue(text,Map.class);
        }
        if(!(structured instanceof Map<?,?>)) throw new IllegalStateException("NX tool returned an invalid result");
        @SuppressWarnings("unchecked") var body=(Map<String,Object>)structured;
        if(json.writeValueAsString(body).length()>32000) throw new IllegalStateException("NX tool result exceeded bound");
        return body;
    }
    @PreDestroy public synchronized void close() { if(client!=null) { client.close();client=null; } }
}
