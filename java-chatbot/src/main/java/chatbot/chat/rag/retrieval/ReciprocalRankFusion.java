package chatbot.chat.rag.retrieval;

import java.util.*;

public final class ReciprocalRankFusion {
    public static final int RANK_CONSTANT=60;
    private ReciprocalRankFusion() {}
    /** Scores are rank-fusion scores, not probabilities. Stable ties make evaluation reproducible. */
    public static List<VectorIndex.Match> fuse(List<List<VectorIndex.Match>> lists) {
        Map<String, Double> scores = new HashMap<>();
        for (var list : lists) {
            Set<String> seen = new HashSet<>();
            int rank = 0;
            for (var match : list) {
                if (seen.add(match.id())) scores.merge(match.id(), 1.0 / (RANK_CONSTANT + ++rank), Double::sum);
            }
        }
        return scores.entrySet().stream().map(e -> new VectorIndex.Match(e.getKey(),e.getValue()))
            .sorted(Comparator.comparingDouble(VectorIndex.Match::score).reversed().thenComparing(VectorIndex.Match::id)).toList();
    }
}
