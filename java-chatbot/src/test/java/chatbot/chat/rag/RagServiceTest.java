package chatbot.chat.rag;

import chatbot.chat.rag.api.ApiModels.*;
import chatbot.chat.rag.config.RagProperties;
import chatbot.chat.rag.domain.SearchHit;
import chatbot.chat.rag.model.ModelGateway;
import chatbot.chat.rag.retrieval.HybridRetriever;
import chatbot.chat.rag.service.RagService;
import chatbot.chat.rag.store.DocumentRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RagServiceTest {
    @Test void invalidCitationAndDeletedSourceCannotBeReportedAsGrounded() {
        var retriever=mock(HybridRetriever.class);var models=mock(ModelGateway.class);
        var repository=mock(DocumentRepository.class);var properties=mock(RagProperties.class);
        var service=new RagService(retriever,models,repository,properties,new JsonMapper());
        var hit=new SearchHit("a","doc","制度.pdf",2,"第二页","限额100元",0.03);
        var prepared=new RagService.Prepared(new HybridRetriever.Result("限额",List.of(hit),"HYBRID",List.of()),null);
        when(repository.readyHits(List.of("a"))).thenReturn(Map.of("a",hit));
        var wrong=service.finish(prepared,"限额100元。[99]");
        assertFalse(wrong.grounded());assertFalse(wrong.answer().contains("[99]"));
        assertFalse(service.finish(prepared,"没有出处的答案").grounded());
        assertTrue(service.finish(prepared,"限额100元。[1]").grounded());
        when(repository.readyHits(List.of("a"))).thenReturn(Map.of());
        var removed=service.finish(prepared,"限额100元。[1]");
        assertFalse(removed.grounded());assertTrue(removed.citations().isEmpty());
    }
}
