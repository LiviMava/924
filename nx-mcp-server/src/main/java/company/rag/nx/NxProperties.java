package company.rag.nx;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;

@ConfigurationProperties("nx")
public record NxProperties(Path bridgeDir,int bridgeTimeoutSeconds,String mcpApiKey,String backend) {}
