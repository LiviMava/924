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
    public ModelGateway(RagProperties properties, ObservationRegistry observations) {
        var c = properties.chat();
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
    public Flux<String> stream(Prompt prompt) {
        if (chat == null) throw new IllegalStateException("聊天模型尚未配置");
        return chat.stream(prompt).map(response -> {
            if (response.getResult() == null || response.getResult().getOutput().getText() == null) return "";
            return response.getResult().getOutput().getText();
        }).filter(text -> !text.isEmpty()).timeout(Duration.ofSeconds(75));
    }
}
