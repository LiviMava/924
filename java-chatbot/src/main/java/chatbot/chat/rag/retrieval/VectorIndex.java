package chatbot.chat.rag.retrieval;

import chatbot.chat.rag.domain.ChunkRecord;
import java.util.List;

public interface VectorIndex {
    record Match(String id, double score) {}
    boolean configured();
    boolean healthy();
    String collection();
    void add(List<ChunkRecord> chunks);
    List<Match> search(String query, int limit);
    void deleteDocument(String collection, String documentId);
}
