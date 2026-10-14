package chatbot.chat.rag.tutorial;

import chatbot.chat.rag.domain.SearchHit;
import chatbot.chat.rag.retrieval.ReciprocalRankFusion;
import chatbot.chat.rag.retrieval.VectorIndex;
import java.util.*;

/** Explains the same RRF computation used in production, retaining branch provenance. */
public final class TutorialFusion {
    private TutorialFusion() {}
    public record Contribution(SearchHit hit,Integer keywordRank,Integer vectorRank,Double keywordScore,Double vectorScore,
                               double keywordContribution,double vectorContribution,double fusedScore) {}
    public record Result(List<SearchHit> hits,List<Contribution> contributions,int candidateCount) {}
    public static Result fuse(List<SearchHit> keywords,List<SearchHit> vectors,int topK) {
        Map<String,Integer> kr=ranks(keywords),vr=ranks(vectors);
        Map<String,SearchHit> kh=index(keywords),vh=index(vectors),all=new HashMap<>(kh);vh.forEach(all::putIfAbsent);
        var fused=ReciprocalRankFusion.fuse(List.of(matches(keywords),matches(vectors)));
        List<SearchHit> hits=new ArrayList<>();List<Contribution> rows=new ArrayList<>();
        for(var match:fused.stream().limit(topK).toList()) {
            var hit=all.get(match.id()).withScore(match.score());hits.add(hit);
            rows.add(new Contribution(hit,kr.get(hit.id()),vr.get(hit.id()),kh.containsKey(hit.id())?kh.get(hit.id()).score():null,
                vh.containsKey(hit.id())?vh.get(hit.id()).score():null,contribution(kr.get(hit.id())),contribution(vr.get(hit.id())),match.score()));
        }
        return new Result(List.copyOf(hits),List.copyOf(rows),fused.size());
    }
    private static double contribution(Integer rank) { return rank==null?0:1.0/(ReciprocalRankFusion.RANK_CONSTANT+rank); }
    private static Map<String,Integer> ranks(List<SearchHit> hits) {
        Map<String,Integer> ranks=new LinkedHashMap<>();for(var hit:hits)if(!ranks.containsKey(hit.id()))ranks.put(hit.id(),ranks.size()+1);return ranks;
    }
    private static Map<String,SearchHit> index(List<SearchHit> hits) {
        Map<String,SearchHit> result=new HashMap<>();hits.forEach(h->result.putIfAbsent(h.id(),h));return result;
    }
    private static List<VectorIndex.Match> matches(List<SearchHit> hits) { return hits.stream().map(h->new VectorIndex.Match(h.id(),h.score())).toList(); }
}
