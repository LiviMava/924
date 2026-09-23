package chatbot.chat;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class ChatSessionTest {

    /** 记录调用、按厂商返回桩 agent 的工厂 */
    private static class RecordingFactory implements Function<Provider, SimpleAgent> {
        final List<Provider> askedFor = new ArrayList<>();
        @Override
        public SimpleAgent apply(Provider p) {
            askedFor.add(p);
            // 桩：不联网，chat 返回固定文本并把整段交给回调
            return new SimpleAgent("sk-test", p.baseUrl, p.model) {
                @Override
                public String chat(String msg, java.util.function.Consumer<String> onDelta) {
                    onDelta.accept("回复:" + msg);
                    return "回复:" + msg;
                }
            };
        }
    }

    @Test
    void 同一厂商复用同一个agent实例() {
        RecordingFactory f = new RecordingFactory();
        ChatSession s = new ChatSession(f);
        SimpleAgent a1 = s.agent(Provider.DEEPSEEK);
        SimpleAgent a2 = s.agent(Provider.DEEPSEEK);
        assertSame(a1, a2, "同厂商应惰性创建一次并复用");
        assertEquals(1, f.askedFor.size());
    }

    @Test
    void 不同厂商各自一个agent() {
        ChatSession s = new ChatSession(new RecordingFactory());
        SimpleAgent d = s.agent(Provider.DEEPSEEK);
        SimpleAgent g = s.agent(Provider.GLM);
        assertNotSame(d, g);
    }

    @Test
    void 切换厂商后回来历史还在() {
        ChatSession s = new ChatSession(new RecordingFactory());
        SimpleAgent d1 = s.agent(Provider.DEEPSEEK);
        s.agent(Provider.GLM);
        SimpleAgent d2 = s.agent(Provider.DEEPSEEK);
        assertSame(d1, d2, "切走再切回，DEEPSEEK 的 agent（及其历史）应保留");
    }
}
