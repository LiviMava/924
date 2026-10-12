package chatbot.chat.rag.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rag.mcp")
public record McpProperties(boolean enabled,String url,String apiKey,int timeoutSeconds) {}
