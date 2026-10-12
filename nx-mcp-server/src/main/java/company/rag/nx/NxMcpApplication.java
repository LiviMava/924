package company.rag.nx;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(NxProperties.class)
public class NxMcpApplication {
    public static void main(String[] args) { SpringApplication.run(NxMcpApplication.class,args); }
}
