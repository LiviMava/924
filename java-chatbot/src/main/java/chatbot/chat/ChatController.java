package chatbot.chat;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletResponse;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "rag.legacy-chat-enabled", havingValue = "true")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** sessionId → 会话。内存态，重启即清（教程定位，不做持久化） */
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public ChatController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 厂商列表 + 是否已配 key，前端下拉框数据源 */
    @GetMapping("/api/providers")
    public List<Map<String, Object>> providers() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Provider p : Provider.values()) {
            Map<String, Object> item = new HashMap<>();
            item.put("name", p.name().toLowerCase(Locale.ROOT));
            item.put("model", p.model);
            item.put("hasKey", p.resolveApiKey() != null);
            list.add(item);
        }
        return list;
    }

    /** SSE 流式聊天：delta 逐段 → done 完整回复；任何错误发 error 事件（HTTP 仍 200） */
    @GetMapping(value = "/api/chat/stream", produces = "text/event-stream")
    public void stream(@RequestParam String sessionId,
                       @RequestParam String vendor,
                       @RequestParam String message,
                       HttpServletResponse response) throws IOException {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        PrintWriter out = response.getWriter();

        // 1. 解析厂商
        Provider provider;
        try {
            provider = Provider.valueOf(vendor.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            send(out, "error", Map.of("message", "未知厂商: " + vendor));
            return;
        }

        // 2. key 检查（环境变量）
        String apiKey = provider.resolveApiKey();
        if (apiKey == null) {
            send(out, "error", Map.of("message",
                    "缺少 " + provider + " 的 API Key，请在服务端配置环境变量 " + provider.envVar + " 后重启"));
            return;
        }

        // 3. 取会话与 agent，流式转发 delta
        ChatSession session = sessions.computeIfAbsent(sessionId, id ->
                new ChatSession(p -> {
                    String k = p.resolveApiKey();   // 惰性创建时才读环境变量
                    if (k == null) throw new IllegalArgumentException(
                            "缺少 " + p + " 的 API Key，请配置 " + p.envVar + " 后重启服务");
                    return new SimpleAgent(k, p.baseUrl, p.model);
                }));
        try {
            String full = session.agent(provider).chat(message, piece -> {
                try {
                    send(out, "delta", Map.of("text", piece));
                } catch (IOException e) {
                    // 客户端断开：停止转发（抛 RuntimeException 中断 SDK 流的消费）
                    throw new RuntimeException("客户端已断开", e);
                }
            });
            send(out, "done", Map.of("text", full));
        } catch (RuntimeException e) {
            log.warn("stream failed, sessionId={}", sessionId, e);
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            try {
                send(out, "error", Map.of("message", msg));
            } catch (IOException ignored) {
                // 客户端已断开：checkError 标志已置位，error 事件无处可发，吞掉避免二次抛出
            }
        }
    }

    /** 新对话：丢弃会话（下次消息自动重建） */
    @PostMapping("/api/chat/reset")
    public Object reset(@RequestParam String sessionId) {
        sessions.remove(sessionId);
        return Map.of("ok", true);
    }

    /** 发一个 SSE 事件：event: <name>\ndata: <json>\n\n */
    private void send(PrintWriter out, String event, Object payload) throws IOException {
        out.write("event: " + event + "\n");
        out.write("data: " + objectMapper.writeValueAsString(payload) + "\n\n");
        out.flush();
        // PrintWriter 会吞掉 IOException，断开只能靠 checkError() 探测
        if (out.checkError()) throw new IOException("客户端已断开");
    }
}
