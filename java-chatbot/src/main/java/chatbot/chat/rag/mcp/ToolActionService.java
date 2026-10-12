package chatbot.chat.rag.mcp;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

@Service
public class ToolActionService {
    public record Action(String id,String tool,String commandId,Map<String,Object> preview,String argumentsHash,
                         String status,Map<String,Object> result,OffsetDateTime expiresAt) {}
    private record Stored(Action view,String owner,String arguments) {}
    private final JdbcTemplate db;
    private final NxMcpClient client;
    private final ObjectMapper json;
    public ToolActionService(JdbcTemplate db,NxMcpClient client,ObjectMapper json) { this.db=db;this.client=client;this.json=json; }
    @PostConstruct void recover() {
        db.update("UPDATE tool_actions SET status='UNKNOWN', updated_at=? WHERE status='EXECUTING'",OffsetDateTime.now(ZoneOffset.UTC));
    }
    public Action stage(String session,Map<String,Object> preview) {
        if(!Boolean.TRUE.equals(preview.get("requiresApproval")) || !(preview.get("planId") instanceof String plan))
            throw new IllegalStateException("MCP preview did not contain a valid plan");
        UUID.fromString(plan);
        String id=UUID.randomUUID().toString(),command=UUID.randomUUID().toString();
        String args=json.writeValueAsString(new TreeMap<>(Map.of("commandId",command,"planId",plan)));
        long expires=((Number)preview.get("expiresAtEpochMs")).longValue();
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        var expiry=Instant.ofEpochMilli(Math.min(expires,now.toInstant().toEpochMilli()+300000)).atOffset(ZoneOffset.UTC);
        if(!expiry.isAfter(now)) throw new IllegalStateException("NX plan already expired");
        db.update("INSERT INTO tool_actions(id,owner_hash,tool_name,command_id,arguments_json,preview_json,arguments_hash,status,created_at,expires_at,updated_at) VALUES(?,?,?,?,?,?,?,'PENDING',?,?,?)",
            id,hash(session),NxMcpClient.WRITE_TOOL,command,args,json.writeValueAsString(preview),hash(args),now,expiry,now);
        return get(session,id);
    }
    public Action get(String session,String id) { return owned(session,id).view(); }
    public Action approve(String session,String id,String expectedHash) {
        var stored=owned(session,id);var action=stored.view();
        if(!MessageDigest.isEqual(action.argumentsHash().getBytes(StandardCharsets.UTF_8),Objects.requireNonNullElse(expectedHash,"").getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"操作参数已变化，请重新查看预览");
        if(!action.status().equals("PENDING")) return action; // duplicate confirmation never re-executes
        var now=OffsetDateTime.now(ZoneOffset.UTC);
        if(!action.expiresAt().isAfter(now)) {
            db.update("UPDATE tool_actions SET status='EXPIRED',updated_at=? WHERE id=? AND status='PENDING'",now,id);
            return get(session,id);
        }
        int claimed=db.update("UPDATE tool_actions SET status='EXECUTING',updated_at=? WHERE id=? AND owner_hash=? AND status='PENDING' AND expires_at>?",now,id,hash(session),now);
        if(claimed==0) return get(session,id);
        try {
            var result=client.applyApproved(map(stored.arguments()));
            String status=Objects.toString(result.get("status"),"UNKNOWN");
            if(!Set.of("SUCCEEDED","FAILED").contains(status)) status="UNKNOWN";
            complete(id,status,result);
        } catch(Exception e) {
            complete(id,"UNKNOWN",Map.of("message","未获得确定执行结果；请查询结果并检查 NX，禁止自动重试","commandId",action.commandId()));
        }
        return get(session,id);
    }
    public Action cancel(String session,String id) {
        owned(session,id);
        db.update("UPDATE tool_actions SET status='CANCELLED',updated_at=? WHERE id=? AND owner_hash=? AND status='PENDING'",OffsetDateTime.now(ZoneOffset.UTC),id,hash(session));
        return get(session,id);
    }
    public Action reconcile(String session,String id) {
        var action=get(session,id);
        if(!Set.of("UNKNOWN","EXECUTING").contains(action.status())) return action;
        var result=client.read("nx_command_status",Map.of("commandId",action.commandId()));
        String status=Objects.toString(result.get("status"),"UNKNOWN");
        if(Set.of("SUCCEEDED","FAILED").contains(status)) complete(id,status,result);
        return get(session,id);
    }
    private void complete(String id,String status,Map<String,Object> result) {
        db.update("UPDATE tool_actions SET status=?,result_json=?,updated_at=? WHERE id=? AND status IN ('EXECUTING','UNKNOWN')",status,json.writeValueAsString(result),OffsetDateTime.now(ZoneOffset.UTC),id);
    }
    private Stored owned(String session,String id) {
        try { UUID.fromString(id); } catch(Exception e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"操作不存在"); }
        var rows=db.query("SELECT * FROM tool_actions WHERE id=? AND owner_hash=?",(rs,n)-> {
            var action=new Action(rs.getString("id"),rs.getString("tool_name"),rs.getString("command_id"),map(rs.getString("preview_json")),
                rs.getString("arguments_hash"),rs.getString("status"),rs.getString("result_json")==null ? Map.of() : map(rs.getString("result_json")),
                rs.getObject("expires_at",OffsetDateTime.class));
            return new Stored(action,rs.getString("owner_hash"),rs.getString("arguments_json"));
        },id,hash(session));
        if(rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"操作不存在或不属于当前会话");
        return rows.get(0);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> map(String value) { return json.readValue(value,Map.class); }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
