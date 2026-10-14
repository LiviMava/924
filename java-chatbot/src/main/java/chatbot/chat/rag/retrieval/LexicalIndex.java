package chatbot.chat.rag.retrieval;

import chatbot.chat.rag.config.RagProperties;
import chatbot.chat.rag.domain.ChunkRecord;
import chatbot.chat.rag.store.DocumentRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.*;
import java.io.IOException;
import java.util.*;

@Component
public class LexicalIndex {
    private final Analyzer analyzer = new SmartChineseAnalyzer();
    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager manager;
    public LexicalIndex(RagProperties properties, DocumentRepository repository) throws IOException {
        directory = FSDirectory.open(properties.dataDir().resolve("lucene"));
        writer = new IndexWriter(directory, new IndexWriterConfig(analyzer));
        // Database is authoritative; rebuild recovers interrupted cross-store operations.
        writer.deleteAll();
        repository.eachReadyChunk(this::write);
        writer.commit();
        manager = new SearcherManager(writer, new SearcherFactory());
    }
    private void write(ChunkRecord chunk) {
        var document = new org.apache.lucene.document.Document();
        document.add(new StringField("id", chunk.id(), Field.Store.YES));
        document.add(new StringField("documentId", chunk.documentId(), Field.Store.NO));
        document.add(new TextField("text", chunk.text(), Field.Store.NO));
        try { writer.updateDocument(new Term("id", chunk.id()), document); }
        catch (IOException e) { throw new IllegalStateException("关键词索引写入失败", e); }
    }
    public synchronized void replace(String documentId, List<ChunkRecord> chunks) {
        try {
            writer.deleteDocuments(new Term("documentId", documentId));
            chunks.forEach(this::write); writer.commit(); manager.maybeRefreshBlocking();
        } catch (IOException e) { throw new IllegalStateException("关键词索引更新失败", e); }
    }
    public void delete(String documentId) { replace(documentId, List.of()); }
    public List<VectorIndex.Match> search(String text, int limit) {
        return search(text,limit,null);
    }
    public List<VectorIndex.Match> search(String text,int limit,String documentId) {
        IndexSearcher searcher = null;
        try {
            Set<String> terms = new LinkedHashSet<>();
            try (var stream = analyzer.tokenStream("text", text)) {
                var attribute = stream.addAttribute(CharTermAttribute.class);
                stream.reset();
                while (stream.incrementToken() && terms.size() < 128) terms.add(attribute.toString());
                stream.end();
            }
            if (terms.isEmpty()) return List.of();
            var query = new BooleanQuery.Builder();
            terms.forEach(term -> query.add(new TermQuery(new Term("text", term)), BooleanClause.Occur.SHOULD));
            query.setMinimumNumberShouldMatch(1);
            searcher = manager.acquire();
            Query scoped=query.build();
            if(documentId!=null)scoped=new BooleanQuery.Builder().add(scoped,BooleanClause.Occur.MUST)
                .add(new TermQuery(new Term("documentId",documentId)),BooleanClause.Occur.FILTER).build();
            var hits = searcher.search(scoped, limit);
            var stored = searcher.storedFields();
            List<VectorIndex.Match> result = new ArrayList<>();
            for (var hit : hits.scoreDocs) result.add(new VectorIndex.Match(stored.document(hit.doc).get("id"), hit.score));
            return result;
        } catch (IOException e) { throw new IllegalStateException("关键词检索失败", e); }
        finally {
            if (searcher != null) try { manager.release(searcher); } catch (IOException ignored) {}
        }
    }
    @PreDestroy public void close() throws IOException { manager.close(); writer.close(); directory.close(); analyzer.close(); }
}
