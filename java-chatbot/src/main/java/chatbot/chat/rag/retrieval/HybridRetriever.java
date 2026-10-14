package chatbot.chat.rag.retrieval;

import chatbot.chat.rag.config.RagProperties;
import chatbot.chat.rag.domain.SearchHit;
import chatbot.chat.rag.store.DocumentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class HybridRetriever {
    public record Result(String query, List<SearchHit> hits, String retrievalMode, List<String> warnings) {}
    private final LexicalIndex lexical;
    private final VectorIndex vectors;
    private final DocumentRepository repository;
    private final Reranker reranker;
    private final RagProperties properties;
    private final MeterRegistry metrics;
    public HybridRetriever(LexicalIndex lexical, VectorIndex vectors, DocumentRepository repository,
                           Reranker reranker, RagProperties properties, MeterRegistry metrics) {
        this.lexical=lexical;this.vectors=vectors;this.repository=repository;this.reranker=reranker;
        this.properties=properties;this.metrics=metrics;
    }
    public Result search(String query, int topK) {
        return search(query,topK,null);
    }
    public Result search(String query,int topK,String documentId) {
        long start=System.nanoTime();
        try {
            List<String> warnings = new ArrayList<>();
            List<List<VectorIndex.Match>> lists = new ArrayList<>();
            if(documentId!=null && repository.find(documentId).filter(d->d.status().equals("READY")).isEmpty())
                return new Result(query,List.of(),"NONE",List.of("所选文档不存在或尚未完成入库"));
            lists.add(lexical.search(query,properties.retrieval().candidates(),documentId));
            String mode="LEXICAL";
            if (vectors.configured()) {
                try {
                    lists.add(vectors.search(query,properties.retrieval().candidates(),documentId));
                    mode="HYBRID";
                    if (repository.lexicalOnlyCount()>0 || repository.staleVectorCount(vectors.collection())>0)
                        warnings.add("部分文档尚未使用当前 Embedding 模型索引，请在文档列表重新索引");
                } catch (RuntimeException e) {
                    warnings.add("向量检索暂时不可用，本次使用关键词检索");
                    metrics.counter("rag.retrieval.fallback","reason","vector_unavailable").increment();
                }
            } else warnings.add("Embedding 未配置，本次为关键词检索");
            var fused = ReciprocalRankFusion.fuse(lists);
            // Authoritative visibility gate: queued, failed and deleted documents never reach model context.
            var available=repository.readyHits(fused.stream().map(VectorIndex.Match::id).toList());
            List<SearchHit> hits=fused.stream().filter(h->available.containsKey(h.id()))
                .filter(h->documentId==null || documentId.equals(available.get(h.id()).documentId()))
                .map(h->available.get(h.id()).withScore(h.score())).toList();
            if (reranker.configured() && !hits.isEmpty()) {
                try { hits=reranker.rerank(query,hits,topK); mode+=reranker.mode().equals("llm")?"_LLM_RERANKED":"_RERANKED"; }
                catch (RuntimeException e) { warnings.add("重排服务暂时不可用，本次使用 RRF 排序"); }
            } else if (!reranker.configured()) warnings.add("未配置重排模型，当前使用 RRF 排序");
            return new Result(query,hits.stream().limit(topK).toList(),mode,List.copyOf(warnings));
        } finally {
            metrics.timer("rag.retrieval.duration").record(System.nanoTime()-start, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }
}
