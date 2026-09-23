package chatbot.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderTest {

    @Test
    void 四家厂商参数齐全() {
        assertTrue(Provider.values().length >= 4, "至少四家厂商");
        for (Provider p : Provider.values()) {
            assertFalse(p.baseUrl.isBlank(), p + " 缺 baseUrl");
            assertFalse(p.model.isBlank(), p + " 缺 model");
            assertFalse(p.envVar.isBlank(), p + " 缺 envVar");
        }
    }

    @Test
    void 环境变量优先于兜底值() {
        assertEquals("env-key", Provider.resolveApiKey("env-key", "fallback-key"));
    }

    @Test
    void 环境变量缺失或空白时用兜底值() {
        assertEquals("fallback-key", Provider.resolveApiKey(null, "fallback-key"));
        assertEquals("fallback-key", Provider.resolveApiKey("   ", "fallback-key"));
    }

    @Test
    void 两者都缺返回null() {
        assertNull(Provider.resolveApiKey(null, ""));
        assertNull(Provider.resolveApiKey(null, null));
        assertNull(Provider.resolveApiKey("   ", "  ")); // 空白视为缺失，组合路径
    }
}
