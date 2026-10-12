package chatbot.chat.rag.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in. Reads and previews only; never invokes nx_apply_block. */
@EnabledIfEnvironmentVariable(named="RAG_TEST_NX_URL",matches=".+")
class NxMcpLiveReadTest {
    @Test void actualClientReadsLiveNxAndPreviewLeavesModelUnchanged() {
        var client=new NxMcpClient(new McpProperties(true,System.getenv("RAG_TEST_NX_URL"),System.getenv("NX_MCP_API_KEY"),30),new JsonMapper());
        try {
            assertEquals(4,client.tools().size());
            var before=client.read("nx_get_context",Map.of());
            assertEquals("NXOPEN",before.get("backend"),"This test requires real NX, never a simulated server");
            assertEquals(true,before.get("hasWorkPart"),"Open a disposable millimeter work part in NX");
            assertEquals("mm",before.get("units"));
            var preview=client.read("nx_preview_block",Map.of("x",0,"y",0,"z",0,"length",100,"width",50,"height",20));
            assertEquals(true,preview.get("requiresApproval"));
            assertNotNull(preview.get("planId"));
            var after=client.read("nx_get_context",Map.of());
            assertEquals(before.get("snapshot"),after.get("snapshot"),"Live preview must not change NX state");
        } finally { client.close(); }
    }
}
