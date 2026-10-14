package chatbot.chat.rag.tutorial;

import chatbot.chat.rag.domain.SearchHit;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

public final class TutorialRanking {
    private TutorialRanking() {}
    /** Keyword baseline over ONLY the candidates, explicitly not a neural reranker. */
    public static List<SearchHit> bm25(String query,List<SearchHit> hits) {
        try(var analyzer=new SmartChineseAnalyzer();var directory=new ByteBuffersDirectory()) {
            try(var writer=new IndexWriter(directory,new IndexWriterConfig(analyzer))) {
                for(int i=0;i<hits.size();i++) {
                    var doc=new Document();doc.add(new StringField("id",Integer.toString(i),Field.Store.YES));
                    doc.add(new TextField("text",hits.get(i).text(),Field.Store.NO));writer.addDocument(doc);
                }
            }
            Map<Integer,Double> scores=new HashMap<>();
            var builder=new BooleanQuery.Builder();Set<String> terms=new LinkedHashSet<>();
            try(var stream=analyzer.tokenStream("text",query)) {
                var attr=stream.addAttribute(CharTermAttribute.class);stream.reset();
                while(stream.incrementToken() && terms.size()<128) terms.add(attr.toString());stream.end();
            }
            terms.forEach(t->builder.add(new TermQuery(new Term("text",t)),BooleanClause.Occur.SHOULD));
            builder.setMinimumNumberShouldMatch(1);
            try(var reader=DirectoryReader.open(directory)) {
                var searcher=new IndexSearcher(reader);searcher.setSimilarity(new BM25Similarity());
                if(!terms.isEmpty()) for(var hit:searcher.search(builder.build(),hits.size()).scoreDocs)
                    scores.put(Integer.parseInt(searcher.storedFields().document(hit.doc).get("id")),(double)hit.score);
            }
            List<Integer> order=new ArrayList<>();for(int i=0;i<hits.size();i++) order.add(i);
            order.sort(Comparator.<Integer>comparingDouble(i->scores.getOrDefault(i,0.0)).reversed().thenComparingInt(i->i));
            return order.stream().map(i->hits.get(i).withScore(scores.getOrDefault(i,0.0))).toList();
        } catch(java.io.IOException e) { throw new IllegalStateException("教学 BM25 对照失败",e); }
    }
    /** Require an exact permutation, so malformed LLM output cannot silently drop/duplicate evidence. */
    public static List<SearchHit> parseOrder(String answer,List<SearchHit> hits,ObjectMapper json) {
        return chatbot.chat.rag.retrieval.ListwiseRanking.parseOrder(answer,hits,json);
    }
}
