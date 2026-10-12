package chatbot.chat.rag.mcp;

import chatbot.chat.rag.api.ApiModels.ChatRequest;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.io.*;
import java.util.*;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/api/v1/agent")
public class NxAgentController {
    public record Approval(@NotBlank String argumentsHash) {}
    private final NxMcpClient client;
    private final NxAgentService agent;
    private final ToolActionService actions;
    private final ObjectMapper json;
    private final Semaphore slots=new Semaphore(2);
    public NxAgentController(NxMcpClient client,NxAgentService agent,ToolActionService actions,ObjectMapper json) {
        this.client=client;this.agent=agent;this.actions=actions;this.json=json;
    }
    @GetMapping("/tools") public Map<String,Object> tools() {
        return Map.of("configured",client.configured(),"tools",client.configured() ? client.tools() : List.of(),"writeApprovalRequired",true);
    }
    @PostMapping("/chat") public NxAgentService.Result chat(@Valid @RequestBody ChatRequest request,HttpServletRequest http) {
        acquire();try { return agent.run(request,http.getSession(true).getId(),(name,event)->{}); } finally { slots.release(); }
    }
    @PostMapping(value="/chat/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public void stream(@Valid @RequestBody ChatRequest request,HttpServletRequest http,HttpServletResponse response) throws IOException {
        String session=http.getSession(true).getId();acquire();
        try {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);response.setCharacterEncoding("UTF-8");
            response.setHeader("Cache-Control","no-store");response.setHeader("X-Accel-Buffering","no");
            var writer=response.getWriter();
            try {
                var result=agent.run(request,session,(name,event)->send(writer,name,event));
                send(writer,"done",result);
            } catch(Exception e) {
                if(!writer.checkError()) send(writer,"error",Map.of("message","NX 规划未完成，请检查 MCP Server、NX 桥接状态或缩小任务范围。模型无法直接修改零件。"));
            }
        } finally { slots.release(); }
    }
    @GetMapping("/actions/{id}") public ToolActionService.Action get(@PathVariable String id,HttpServletRequest http) {
        return actions.get(http.getSession(true).getId(),id);
    }
    @PostMapping("/actions/{id}/approve") public ToolActionService.Action approve(@PathVariable String id,@Valid @RequestBody Approval request,HttpServletRequest http) {
        acquire();try { return actions.approve(http.getSession(true).getId(),id,request.argumentsHash()); } finally { slots.release(); }
    }
    @PostMapping("/actions/{id}/cancel") public ToolActionService.Action cancel(@PathVariable String id,HttpServletRequest http) {
        return actions.cancel(http.getSession(true).getId(),id);
    }
    @PostMapping("/actions/{id}/reconcile") public ToolActionService.Action reconcile(@PathVariable String id,HttpServletRequest http) {
        acquire();try { return actions.reconcile(http.getSession(true).getId(),id); } finally { slots.release(); }
    }
    private void acquire() { if(!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"NX 请求较多，请稍后重试"); }
    private void send(PrintWriter writer,String name,Object event) {
        writer.write("event: "+name+"\ndata: "+json.writeValueAsString(event)+"\n\n");writer.flush();
        if(writer.checkError()) throw new IllegalStateException("NX chat disconnected");
    }
}
