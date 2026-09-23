package chatbot.chat.rag.api;

import chatbot.chat.rag.config.RagProperties;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiAccessFilter extends OncePerRequestFilter {
    private final RagProperties properties;
    private final ObjectMapper json;
    public ApiAccessFilter(RagProperties properties,ObjectMapper json) { this.properties=properties;this.json=json; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        String requestId=UUID.randomUUID().toString();MDC.put("requestId",requestId);
        response.setHeader("X-Request-Id",requestId);
        response.setHeader("X-Content-Type-Options","nosniff");
        response.setHeader("Referrer-Policy","no-referrer");
        response.setHeader("X-Frame-Options","DENY");
        try {
            // Container-normalized servlet path excludes contextPath. Fail closed for every
            // route except an explicit read-only public allowlist, including encoded/matrix URLs.
            String path=request.getServletPath();
            boolean publicPath=request.getMethod().equals("GET") && (Set.of("/", "/index.html", "/rag.js", "/rag.css",
                "/favicon.ico", "/api/v1/system", "/actuator/health", "/swagger-ui.html", "/v3/api-docs").contains(path)
                || path.startsWith("/swagger-ui/") || path.startsWith("/v3/api-docs/"));
            boolean protectedPath=!publicPath;
            if (protectedPath) response.setHeader("Cache-Control","no-store");
            if (protectedPath && !Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())) {
                String origin=request.getHeader("Origin");
                boolean sameOrigin=true;
                if(origin!=null) {
                    try { sameOrigin=Objects.equals(URI.create(origin).getRawAuthority(),request.getHeader("Host")); }
                    catch(IllegalArgumentException e) { sameOrigin=false; }
                }
                if(!sameOrigin || "cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
                    reject(response,403,"跨站写入请求被拒绝",requestId);return;
                }
            }
            if(protectedPath && !publicPath && !properties.apiKey().isBlank()) {
                String supplied=Objects.requireNonNullElse(request.getHeader("Authorization"),"");
                String expected="Bearer "+properties.apiKey();
                if(!MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8))) {
                    response.setHeader("WWW-Authenticate","Bearer");
                    reject(response,401,"请提供有效的服务访问令牌",requestId);return;
                }
            }
            chain.doFilter(request,response);
        } finally { MDC.remove("requestId"); }
    }
    private void reject(HttpServletResponse response,int status,String detail,String requestId) throws IOException {
        response.setStatus(status);response.setContentType("application/problem+json");response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json.writeValueAsString(Map.of("type","about:blank","title","访问被拒绝",
            "status",status,"detail",detail,"requestId",requestId)));
    }
}
