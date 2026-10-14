package chatbot.chat.rag.tutorial;

import chatbot.chat.rag.domain.SearchHit;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TutorialFusionTest {
    private SearchHit hit(String id,double score) { return new SearchHit(id,"doc","handbook.pdf",1,"policy",id,score); }
    @Test void sharedEvidenceWinsDespiteDifferentScoreScalesAndExplainsEachContribution() {
        var result=TutorialFusion.fuse(List.of(hit("keyword",1000),hit("shared",500)),
            List.of(hit("vector",.99),hit("shared",.9)),2);
        assertEquals(3,result.candidateCount());
        assertEquals(List.of("shared","keyword"),result.hits().stream().map(SearchHit::id).toList());
        var shared=result.contributions().get(0);
        assertEquals(2,shared.keywordRank());assertEquals(2,shared.vectorRank());
        assertEquals(500,shared.keywordScore());assertEquals(.9,shared.vectorScore());
        assertEquals(1.0/62,shared.keywordContribution(),1e-12);
        assertEquals(2.0/62,shared.fusedScore(),1e-12);
        var single=result.contributions().get(1);
        assertNull(single.vectorRank());assertNull(single.vectorScore());assertEquals(0,single.vectorContribution());
    }
    @Test void duplicateCandidatesCannotMultiplyVotesAndSingleBranchWorks() {
        var result=TutorialFusion.fuse(List.of(hit("a",8),hit("a",7),hit("b",6)),List.of(),24);
        assertEquals(2,result.candidateCount());assertEquals(2,result.hits().size());
        assertEquals(2,result.contributions().get(1).keywordRank());
        assertEquals(1.0/62,result.hits().get(1).score(),1e-12);
        assertTrue(TutorialFusion.fuse(List.of(),List.of(),10).hits().isEmpty());
    }
}
