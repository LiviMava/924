package company.rag.nx;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Local IPC only. The NX journal consumes files on its UI thread, never on a Java worker. */
@Component
@ConditionalOnProperty(name="nx.backend",havingValue="bridge",matchIfMissing=true)
public class FileNxBackend implements NxBackend {
    private final Path root;
    private final int timeout;
    private final ObjectMapper json;
    public FileNxBackend(NxProperties properties,ObjectMapper json) throws Exception {
        root=properties.bridgeDir().toAbsolutePath().normalize();timeout=properties.bridgeTimeoutSeconds();this.json=json;
        if(timeout<1 || timeout>60) throw new IllegalArgumentException("NX bridge timeout out of range");
        for(String dir:List.of("inbox","outbox","receipts")) Files.createDirectories(root.resolve(dir));
    }
    @Override public Map<String,Object> execute(String operation,Map<String,Object> args,String commandId) {
        if(!commandId.matches("[a-f0-9-]{36}")) throw new IllegalArgumentException("Invalid commandId");
        try {
            Path heartbeat=root.resolve("heartbeat.json");
            if(!Files.exists(heartbeat) || Files.getLastModifiedTime(heartbeat).toInstant().isBefore(Instant.now().minusSeconds(10)))
                throw new IllegalStateException("NX bridge offline: load NxBridge.dll inside NX 2512");
            String id=UUID.randomUUID().toString();
            Path response=root.resolve("outbox").resolve(id+".json");
            long deadline=System.currentTimeMillis()+timeout*1000L;
            Map<String,Object> request=Map.of("requestId",id,"commandId",commandId,"operation",operation,
                "arguments",args,"deadlineEpochMs",deadline);
            Path temp=root.resolve("inbox").resolve(id+".tmp");
            Files.writeString(temp,json.writeValueAsString(request),StandardOpenOption.CREATE_NEW);
            Files.move(temp,root.resolve("inbox").resolve(id+".json"),StandardCopyOption.ATOMIC_MOVE);
            while(System.currentTimeMillis()<deadline) {
                if(Files.exists(response)) {
                    if(Files.size(response)>65536) throw new IllegalStateException("NX result too large");
                    @SuppressWarnings("unchecked") Map<String,Object> body=json.readValue(Files.readString(response),Map.class);
                    Files.delete(response);
                    return body;
                }
                Thread.sleep(100);
            }
            // No replay here. A timed-out mutation may still have committed in NX.
            throw new IllegalStateException("NX result unknown after timeout; query nx_command_status with the same commandId");
        } catch(InterruptedException e) {
            Thread.currentThread().interrupt();throw new IllegalStateException("NX wait interrupted; execution outcome may be unknown");
        } catch(IllegalStateException e) { throw e; }
        catch(Exception e) { throw new IllegalStateException("NX bridge IPC failed; check project-local bridge logs",e); }
    }
}
