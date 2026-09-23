package chatbot.chat.rag.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rag")
public record RagProperties(Path dataDir, String apiKey, Model chat, Embedding embedding,
                            Qdrant qdrant, Retrieval retrieval) {
    public record Model(String baseUrl, String apiKey, String model) {
        public boolean configured() { return present(baseUrl) && present(apiKey) && present(model); }
    }
    public record Embedding(String baseUrl, String apiKey, String model, int dimensions) {
        public boolean configured() { return present(baseUrl) && present(apiKey) && present(model); }
    }
    public record Qdrant(String host, int grpcPort, String httpUrl, String apiKey,
                         boolean useTls, String collection) {}
    public record Retrieval(int candidates, int topK, double minVectorScore,
                            String rerankUrl, String rerankApiKey, String rerankModel) {}
    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
