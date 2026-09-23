package chatbot.chat.rag.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReciprocalRankFusionTest {
    @Test
    void sharedResultOutranksSingleRouteWinnersRegardlessOfRawScoreScales() {
        var result = ReciprocalRankFusion.fuse(List.of(
                List.of(match("keyword-only", 1000), match("shared", 500)),
                List.of(match("vector-only", .99), match("shared", .91))));

        assertEquals(List.of("shared", "keyword-only", "vector-only"), ids(result));
        assertEquals(2.0 / 62, result.get(0).score(), 1e-12);
        assertEquals(1.0 / 61, result.get(1).score(), 1e-12);
        assertEquals(1.0 / 61, result.get(2).score(), 1e-12);
    }

    @Test
    void duplicateIdsWithinOneRouteNeitherAddVotesNorConsumeRanks() {
        var withDuplicates = ReciprocalRankFusion.fuse(List.of(
                List.of(match("a", 100), match("a", 90), match("b", 80), match("b", 70)),
                List.of(match("b", .8), match("c", .7), match("b", .6))));
        var deduplicated = ReciprocalRankFusion.fuse(List.of(
                List.of(match("a", 100), match("b", 80)),
                List.of(match("b", .8), match("c", .7))));

        assertEquals(deduplicated, withDuplicates);
        assertEquals(List.of("b", "a", "c"), ids(withDuplicates));
        assertEquals(1.0 / 62 + 1.0 / 61, withDuplicates.get(0).score(), 1e-12);
    }

    @Test
    void equalScoresHaveDeterministicIdOrderAndEmptyRoutesAreHarmless() {
        var result = ReciprocalRankFusion.fuse(List.of(
                List.of(), List.of(match("z", .5)), List.of(match("a", 900))));
        assertEquals(List.of("a", "z"), ids(result));
        assertTrue(ReciprocalRankFusion.fuse(List.of()).isEmpty());
        assertTrue(ReciprocalRankFusion.fuse(List.of(List.of(), List.of())).isEmpty());
    }

    private static VectorIndex.Match match(String id, double score) {
        return new VectorIndex.Match(id, score);
    }

    private static List<String> ids(List<VectorIndex.Match> matches) {
        return matches.stream().map(VectorIndex.Match::id).toList();
    }
}
