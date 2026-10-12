package company.rag.nx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class FileNxBackendTest {
    @TempDir Path dir;
    @Test void atomicQueueRoundTripCarriesCommandAndDeadline() throws Exception {
        var json=new JsonMapper();var backend=new FileNxBackend(new NxProperties(dir,2,"unused","bridge"),json);
        Files.writeString(dir.resolve("heartbeat.json"),"{}");String command=UUID.randomUUID().toString();
        var executor=Executors.newSingleThreadExecutor();
        try {
            var request=executor.submit(()->backend.execute("context",Map.of(),command));
            Path file=null;long deadline=System.currentTimeMillis()+2000;
            while(file==null && System.currentTimeMillis()<deadline) {
                try(var files=Files.list(dir.resolve("inbox"))) { file=files.filter(p->p.toString().endsWith(".json")).findFirst().orElse(null); }
                if(file==null) Thread.sleep(20);
            }
            assertNotNull(file);var body=json.readTree(Files.readString(file));assertEquals(command,body.path("commandId").asText());
            assertTrue(body.path("deadlineEpochMs").asLong()>System.currentTimeMillis());
            Path response=dir.resolve("outbox").resolve(body.path("requestId").asText()+".json");
            Files.writeString(response,"{\"backend\":\"NXOPEN\",\"hasWorkPart\":true}");
            assertEquals("NXOPEN",request.get(3,TimeUnit.SECONDS).get("backend"));assertFalse(Files.exists(response));
        } finally { executor.shutdownNow(); }
    }
    @Test void staleHeartbeatRejectsBeforeQueueingAndTimeoutNeverReplays() throws Exception {
        var backend=new FileNxBackend(new NxProperties(dir,1,"unused","bridge"),new JsonMapper());
        assertThrows(IllegalStateException.class,()->backend.execute("context",Map.of(),UUID.randomUUID().toString()));
        try(var files=Files.list(dir.resolve("inbox"))) { assertEquals(0,files.count()); }
        Files.writeString(dir.resolve("heartbeat.json"),"{}");
        assertThrows(IllegalStateException.class,()->backend.execute("create_block",Map.of(),UUID.randomUUID().toString()));
        try(var files=Files.list(dir.resolve("inbox"))) { assertEquals(1,files.filter(p->p.toString().endsWith(".json")).count(),"one submission only; NX checks deadline before any mutation"); }
    }
}
