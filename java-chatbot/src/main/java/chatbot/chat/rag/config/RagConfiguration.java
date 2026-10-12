package chatbot.chat.rag.config;

import java.io.IOException;
import java.nio.file.Files;
import java.net.InetAddress;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.core.env.Environment;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springframework.context.annotation.Bean;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({RagProperties.class,chatbot.chat.rag.mcp.McpProperties.class})
public class RagConfiguration {
    public RagConfiguration(RagProperties properties, Environment environment) throws IOException {
        for (String name : new String[]{"tmp", "uploads", "lucene"}) {
            Files.createDirectories(properties.dataDir().resolve(name));
        }
        boolean local = InetAddress.getByName(environment.getProperty("server.address", "127.0.0.1")).isLoopbackAddress();
        if ((!local || environment.matchesProfiles("prod")) && properties.apiKey().isBlank()) {
            throw new IllegalStateException("非本机监听或 prod 环境必须配置 RAG_API_KEY");
        }
        if (properties.embedding().dimensions() < 1 || properties.embedding().dimensions() > 65536) {
            throw new IllegalArgumentException("RAG_EMBEDDING_DIMENSIONS 超出有效范围");
        }
    }
    @Bean OpenAPI openAPI() {
        return new OpenAPI().info(new Info().title("Company Knowledge API").version("v1")
                .description("共享公司知识库；Bearer 服务令牌保护。生产集成可在入口接入公司 SSO/API 网关。"))
            .components(new Components().addSecuritySchemes("bearerAuth",
                    new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
