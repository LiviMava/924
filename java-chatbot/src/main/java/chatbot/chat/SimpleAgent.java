package chatbot.chat;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionChunk;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;
import com.openai.models.chat.completions.ChatCompletionUserMessageParam;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 统一大模型客户端（服务化版）：构造器 (apiKey, baseUrl, model)，
 * chat() 把流式 delta 交给回调 onDelta，由调用方决定怎么展示（SSE 推送/收集/丢弃）。
 * 类内不做任何打印。
 */
public class SimpleAgent {

    private final OpenAIClient client;
    private final String model;
    private final List<ChatCompletionMessageParam> conversation = new ArrayList<>();

    public SimpleAgent(String apiKey, String baseUrl, String model) {
        this.client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        this.model = model;
    }

    /**
     * 处理一条用户消息：追加进历史 → 流式请求 → 每个 delta 片段交给 onDelta →
     * 拼出完整回复写回历史并返回。多轮记忆靠 conversation 列表。
     * 注意：先入列再请求，请求失败时历史里留下无应答的 user 消息，
     * OpenAI 兼容端点都接受，调用方可继续下一轮。
     * 回调契约：onDelta 在调用线程上按序单线程执行；onDelta 抛出的异常会先关闭
     * 底层 HTTP 流再原样上抛——调用方可借此中断流式转发（如客户端断开）；
     * 异常时本条回复不入历史。实例非线程安全：同一会话同时只允许一个 chat() 在跑。
     */
    public String chat(String userMessage, Consumer<String> onDelta) {
        conversation.add(ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder().content(userMessage).build()));

        ChatCompletionCreateParams params = ChatCompletionCreateParams.builder()
                .model(model)
                .messages(conversation)
                .build();

        StringBuilder fullReply = new StringBuilder();
        try (StreamResponse<ChatCompletionChunk> stream =
                     client.chat().completions().createStreaming(params)) {
            stream.stream().forEach(chunk -> {
                if (chunk.choices().isEmpty()) return; // 末尾 usage-only chunk
                chunk.choices().get(0).delta().content().ifPresent(piece -> {
                    onDelta.accept(piece);             // 流式：交给调用方
                    fullReply.append(piece);           // 同时拼接完整回复
                });
            });
        }

        conversation.add(ChatCompletionMessageParam.ofAssistant(
                ChatCompletionAssistantMessageParam.builder()
                        .content(fullReply.toString()).build()));
        return fullReply.toString();
    }
}
