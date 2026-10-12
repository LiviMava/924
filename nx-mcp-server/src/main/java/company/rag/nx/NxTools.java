package company.rag.nx;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class NxTools {
    public record Plan(String id,Map<String,Object> parameters,Map<String,Object> context,long expiresAt) {}
    private final Map<String,Plan> plans=new ConcurrentHashMap<>();
    private final Map<String,String> planCommands=new ConcurrentHashMap<>();
    private final NxBackend backend;
    private final ObjectMapper json;
    public NxTools(NxBackend backend,ObjectMapper json) { this.backend=backend;this.json=json; }
    @Bean public List<SyncToolSpecification> nxToolSpecifications() {
        Map<String,Object> dimensions=new LinkedHashMap<>();
        for(String axis:List.of("x","y","z")) dimensions.put(axis,Map.of("type","number","minimum",-100000,"maximum",100000));
        for(String axis:List.of("length","width","height")) dimensions.put(axis,Map.of("type","number","exclusiveMinimum",0,"maximum",10000));
        return List.of(
            tool("nx_get_context","Read live NX work-part state, units and snapshot. No model changes.",schema(Map.of(),List.of()),true),
            tool("nx_preview_block","Prepare a new standalone rectangular solid. All dimensions and origin coordinates are millimeters in absolute coordinates. Does not modify NX. User must confirm the returned plan before execution.",
                schema(dimensions,List.of("x","y","z","length","width","height")),true),
            tool("nx_apply_block","Apply a previously previewed plan after human approval. Rejects expired plans or changed NX state. commandId is an application-generated UUID used for deduplication and reconciliation.",
                schema(Map.of("planId",Map.of("type","string","format","uuid"),"commandId",Map.of("type","string","format","uuid")),List.of("planId","commandId")),false),
            tool("nx_command_status","Read a previously submitted command receipt. Does not retry a mutation.",
                schema(Map.of("commandId",Map.of("type","string","format","uuid")),List.of("commandId")),true)
        );
    }
    private SyncToolSpecification tool(String name,String description,Map<String,Object> schema,boolean readOnly) {
        var annotations=McpSchema.ToolAnnotations.builder().readOnlyHint(readOnly).destructiveHint(false)
            .idempotentHint(true).openWorldHint(false).build();
        var tool=McpSchema.Tool.builder().name(name).description(description).inputSchema(schema).annotations(annotations).build();
        return new SyncToolSpecification(tool,(exchange,request)-> {
            try {
                var result=invoke(name,request.arguments());
                return McpSchema.CallToolResult.builder().structuredContent(result).addTextContent(json.writeValueAsString(result))
                    .isError("FAILED".equals(result.get("status")) || "UNKNOWN".equals(result.get("status"))).build();
            } catch(IllegalArgumentException e) {
                return McpSchema.CallToolResult.builder().isError(true).addTextContent(e.getMessage()).build();
            } catch(Exception e) {
                return McpSchema.CallToolResult.builder().isError(true).addTextContent("NX operation unavailable; check bridge or reconcile commandId. No automatic retry.").build();
            }
        });
    }
    public Map<String,Object> invoke(String tool,Map<String,Object> args) {
        args=args==null ? Map.of() : args;
        if(tool.equals("nx_get_context")) { requireKeys(args,Set.of());return backend.execute("context",Map.of(),UUID.randomUUID().toString()); }
        if(tool.equals("nx_command_status")) {
            requireKeys(args,Set.of("commandId"));String id=uuid(args,"commandId");
            return backend.execute("status",Map.of("commandId",id),UUID.randomUUID().toString());
        }
        if(tool.equals("nx_preview_block")) {
            requireKeys(args,Set.of("x","y","z","length","width","height"));
            var parameters=new LinkedHashMap<String,Object>();
            for(String key:List.of("x","y","z","length","width","height")) {
                if(!(args.get(key) instanceof Number n)) throw new IllegalArgumentException("Numeric dimensions required");
                double v=n.doubleValue();boolean coordinate=Set.of("x","y","z").contains(key);
                if(!Double.isFinite(v) || (coordinate ? Math.abs(v)>100000 : v<=0 || v>10000)) throw new IllegalArgumentException("Dimensions out of bounds");
                parameters.put(key,v);
            }
            var context=backend.execute("context",Map.of(),UUID.randomUUID().toString());
            if(!Boolean.TRUE.equals(context.get("hasWorkPart"))) throw new IllegalArgumentException("Open a work part in NX first");
            if(!"mm".equals(context.get("units"))) throw new IllegalArgumentException("This demo supports millimeter work parts only");
            plans.entrySet().removeIf(e->{ if(e.getValue().expiresAt()<System.currentTimeMillis()) { planCommands.remove(e.getKey());return true; } return false; });
            if(plans.size()>=1024) throw new IllegalStateException("Too many pending plans");
            String id=UUID.randomUUID().toString();long expiry=System.currentTimeMillis()+300000;
            var plan=new Plan(id,Map.copyOf(parameters),Map.copyOf(context),expiry);plans.put(id,plan);
            return Map.of("planId",id,"operation","CREATE_BLOCK","parameters",parameters,"context",context,
                "expiresAtEpochMs",expiry,"requiresApproval",true,"summary","Create standalone block; no save, overwrite, delete or Boolean union");
        }
        if(tool.equals("nx_apply_block")) {
            requireKeys(args,Set.of("planId","commandId"));String id=uuid(args,"planId"),command=uuid(args,"commandId");
            Plan plan=plans.get(id);
            if(plan==null || plan.expiresAt()<System.currentTimeMillis()) throw new IllegalArgumentException("Preview expired or server restarted; preview again");
            String previous=planCommands.putIfAbsent(id,command);
            if(previous!=null && !previous.equals(command)) throw new IllegalArgumentException("Plan already claimed by another command");
            var arguments=new LinkedHashMap<>(plan.parameters());
            arguments.put("expectedSnapshot",plan.context().get("snapshot"));
            arguments.put("instanceId",plan.context().get("instanceId"));
            return backend.execute("create_block",arguments,command);
        }
        throw new IllegalArgumentException("Tool is not allowed");
    }
    private static Map<String,Object> schema(Map<String,Object> properties,List<String> required) {
        return Map.of("type","object","properties",properties,"required",required,"additionalProperties",false);
    }
    private static void requireKeys(Map<String,Object> args,Set<String> keys) {
        if(!args.keySet().equals(keys)) throw new IllegalArgumentException("Unexpected or missing tool arguments");
    }
    private static String uuid(Map<String,Object> args,String key) {
        if(!(args.get(key) instanceof String value)) throw new IllegalArgumentException("UUID required");
        try { return UUID.fromString(value).toString(); } catch(Exception e) { throw new IllegalArgumentException("UUID required"); }
    }
}
