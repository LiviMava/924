package chatbot.chat.rag.model;

import chatbot.chat.rag.config.RagProperties;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.openai.*;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.List;

@Component
public class ModelGateway {
    private final OpenAiChatModel chat;
    private final OpenAiEmbeddingModel embedding;
    private final boolean deepSeekToolMode;
    public ModelGateway(RagProperties properties, ObservationRegistry observations) {
        var c = properties.chat();
        deepSeekToolMode=c.configured() && "api.deepseek.com".equalsIgnoreCase(java.net.URI.create(c.baseUrl()).getHost());
        chat = c.configured() ? OpenAiChatModel.builder()
            .options(OpenAiChatOptions.builder().baseUrl(c.baseUrl()).apiKey(c.apiKey())
                .model(c.model()).timeout(Duration.ofSeconds(60)).maxRetries(1).build())
            .observationRegistry(observations).build() : null;
        var e = properties.embedding();
        embedding = e.configured() ? OpenAiEmbeddingModel.builder().metadataMode(MetadataMode.NONE)
            .options(OpenAiEmbeddingOptions.builder().baseUrl(e.baseUrl()).apiKey(e.apiKey())
                .model(e.model()).timeout(Duration.ofSeconds(45)).maxRetries(1).build())
            .observationRegistry(observations).build() : null;
    }
    public boolean chatConfigured() { return chat != null; }
    public boolean embeddingConfigured() { return embedding != null; }
    public List<float[]> embed(List<String> texts) {
        if (embedding == null) throw new IllegalStateException("Embedding 服务尚未配置");
        return embedding.embed(texts);
    }
    public String answer(Prompt prompt) {
        if (chat == null) throw new IllegalStateException("聊天模型尚未配置");
        var response = chat.call(prompt);
        return response.getResult().getOutput().getText();
    }
    /** A bounded text-only call for listwise teaching demos; never installs executable tools. */
    public String tutorialAnswer(Prompt prompt) {
        return boundedAnswer(prompt);
    }
    public String rankingAnswer(Prompt prompt) { return boundedAnswer(prompt); }
    private String boundedAnswer(Prompt prompt) {
        if(chat==null) throw new IllegalStateException("聊天模型尚未配置");
        var options=OpenAiChatOptions.builder().model(chat.getOptions().getModel())
            .timeout(Duration.ofSeconds(45)).maxRetries(0);
        if(deepSeekToolMode) options.extraBody(java.util.Map.of("thinking",java.util.Map.of("type","disabled")));
        var response=chat.call(new Prompt(prompt.getInstructions(),options.build()));
        return response.getResult().getOutput().getText();
    }
    /** Return native tool calls to the host; the model SDK must never execute them implicitly. */
    public org.springframework.ai.chat.model.ChatResponse agentTurn(Prompt prompt,List<org.springframework.ai.tool.ToolCallback> tools) {
        return agentTurn(prompt,tools,Duration.ofSeconds(45));
    }
    public org.springframework.ai.chat.model.ChatResponse agentTurn(Prompt prompt,List<org.springframework.ai.tool.ToolCallback> tools,Duration timeout) {
        if(chat==null) throw new IllegalStateException("聊天模型尚未配置");
        // Spring AI 2.x executes tools in ToolCallAdvisor; direct ChatModel calls return tool requests.
        // This gateway deliberately installs no ToolCallAdvisor.
        // The builder has its own default model; always preserve the configured provider model.
        var builder=OpenAiChatOptions.builder().model(chat.getOptions().getModel()).toolCallbacks(tools)
            .strict(false)
            .parallelToolCalls(false).maxRetries(0).timeout(timeout);
        // This bounded planner keeps public chat history, not provider-specific reasoning state.
        // DeepSeek defaults to thinking mode, which requires replaying reasoning for every tool turn.
        if(deepSeekToolMode) builder.extraBody(java.util.Map.of("thinking",java.util.Map.of("type","disabled")));
        return chat.call(new Prompt(prompt.getInstructions(),builder.build()));
    }
    public Flux<String> stream(Prompt prompt) {
        if (chat == null) throw new IllegalStateException("聊天模型尚未配置");
        return chat.stream(prompt).map(response -> {
            if (response.getResult() == null || response.getResult().getOutput().getText() == null) return "";
            return response.getResult().getOutput().getText();
        }).filter(text -> !text.isEmpty()).timeout(Duration.ofSeconds(75));
    }
}
