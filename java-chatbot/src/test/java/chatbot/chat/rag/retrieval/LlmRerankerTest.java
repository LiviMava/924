package chatbot.chat.rag.retrieval;

import chatbot.chat.rag.config.RagProperties;
import chatbot.chat.rag.domain.SearchHit;
import chatbot.chat.rag.model.ModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LlmRerankerTest {
    private final ModelGateway model=mock(ModelGateway.class);
    private final List<SearchHit> hits=List.of(
        new SearchHit("cover","doc","handbook.pdf",1,"cover","Employee handbook cover",.08),
        new SearchHit("fact","doc","handbook.pdf",13,"breaks","One paid 15-minute break per four hours",.02));
    private Reranker ranker() {
        var properties=mock(RagProperties.class);
        when(properties.retrieval()).thenReturn(new RagProperties.Retrieval(30,6,.45,"","",""));
        when(model.chatConfigured()).thenReturn(true);
        var result=new Reranker(properties,new JsonMapper(),model);ReflectionTestUtils.setField(result,"mode","llm");return result;
    }
    @Test void listwiseRankingPromotesEvidenceAndPreservesOriginalScore() {
        var ranker=ranker();when(model.rankingAnswer(any())).thenReturn("{\"order\":[1]}");
        assertTrue(ranker.configured());var result=ranker.rerank("How long are rest breaks?",hits,1);
        assertEquals(List.of(hits.get(1)),result);
        verify(model).rankingAnswer(argThat(p->p.toString().contains("untrusted data") && p.toString().contains("15-minute")));
    }
    @Test void rejectsMissingDuplicateFractionalOrOverflowedIndices() {
        var ranker=ranker();
        for(String invalid:List.of("{\"order\":[1]}","{\"order\":[1,1]}","{\"order\":[1,0.0]}","{\"order\":[1,4294967296]}")) {
            when(model.rankingAnswer(any())).thenReturn(invalid);
            assertThrows(IllegalStateException.class,()->ranker.rerank("breaks",hits,2));
        }
    }
}
