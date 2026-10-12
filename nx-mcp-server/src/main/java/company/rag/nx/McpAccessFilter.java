package company.rag.nx;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.net.URI;
import java.util.Objects;

@Component
public class McpAccessFilter extends OncePerRequestFilter {
    private final String token;
    public McpAccessFilter(NxProperties properties) {
        token=properties.mcpApiKey();
        if(token==null || token.length()<24) throw new IllegalStateException("Set a separate NX_MCP_API_KEY (at least 24 characters)");
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws IOException,ServletException {
        response.setHeader("Cache-Control","no-store");
        if(request.getMethod().equals("GET") && request.getServletPath().equals("/actuator/health")) { chain.doFilter(request,response);return; }
        String origin=request.getHeader("Origin");
        if(origin!=null) {
            boolean same=false;
            try { same=Objects.equals(URI.create(origin).getRawAuthority(),request.getHeader("Host")); } catch(Exception ignored) {}
            if(!same) { response.sendError(403);return; }
        }
        String actual=Objects.requireNonNullElse(request.getHeader("Authorization"),"");
        if(!MessageDigest.isEqual(("Bearer "+token).getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8))) {
            response.setHeader("WWW-Authenticate","Bearer");response.sendError(401);return;
        }
        chain.doFilter(request,response);
    }
}
