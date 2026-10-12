package company.rag.nx;

import java.util.Map;

public interface NxBackend {
    Map<String,Object> execute(String operation,Map<String,Object> arguments,String commandId);
}
