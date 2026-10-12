package company.rag.nx;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;

/** Explicit simulation. Never advertises itself as a connected NX instance. */
@Component
@ConditionalOnProperty(name="nx.backend",havingValue="mock")
public class MockNxBackend implements NxBackend {
    private int revision;
    private final Map<String,Map<String,Object>> receipts=new HashMap<>();
    @Override public synchronized Map<String,Object> execute(String operation,Map<String,Object> args,String commandId) {
        var context=Map.<String,Object>of("backend","MOCK","instanceId","mock-nx","partName","MockPart.prt",
            "units","mm","snapshot","mock-"+revision,"featureCount",revision,"hasWorkPart",true);
        if(operation.equals("context")) return context;
        if(operation.equals("status")) return receipts.getOrDefault((String)args.get("commandId"),Map.of("status","NOT_FOUND","backend","MOCK"));
        if(receipts.containsKey(commandId)) return receipts.get(commandId);
        if(!Objects.equals(args.get("expectedSnapshot"),context.get("snapshot")))
            return Map.of("status","FAILED","message","NX model changed; preview again","backend","MOCK");
        revision++;
        var result=Map.<String,Object>of("status","SUCCEEDED","backend","MOCK","commandId",commandId,
            "featureName","BLOCK("+revision+")","snapshot","mock-"+revision);
        receipts.put(commandId,result);return result;
    }
}
