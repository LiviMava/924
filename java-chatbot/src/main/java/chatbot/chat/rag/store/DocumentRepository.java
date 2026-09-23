package chatbot.chat.rag.store;

import chatbot.chat.rag.domain.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DocumentRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<DocumentRecord> DOCUMENT = (rs, n) -> new DocumentRecord(
        rs.getString("id"), rs.getString("filename"), rs.getString("sha256"), rs.getString("status"),
        rs.getInt("chunk_count"), rs.getString("index_mode"), rs.getString("vector_collection"),
        rs.getString("error"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
        rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    private static final RowMapper<ChunkRecord> CHUNK = (rs, n) -> new ChunkRecord(
        rs.getString("id"), rs.getString("document_id"), rs.getInt("chunk_index"),
        rs.getString("content"), (Integer)rs.getObject("page_number"), rs.getString("section_title"));
    public DocumentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<DocumentRecord> list(int limit, int offset) {
        return jdbc.query("SELECT * FROM kb_documents ORDER BY created_at DESC LIMIT ? OFFSET ?", DOCUMENT, limit, offset);
    }
    public Optional<DocumentRecord> find(String id) {
        return jdbc.query("SELECT * FROM kb_documents WHERE id=?", DOCUMENT, id).stream().findFirst();
    }
    public Optional<DocumentRecord> byHash(String hash) {
        return jdbc.query("SELECT * FROM kb_documents WHERE sha256=?", DOCUMENT, hash).stream().findFirst();
    }
    public DocumentRecord insert(String id, String name, String hash) {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update("INSERT INTO kb_documents(id,filename,sha256,status,created_at,updated_at) VALUES(?,?,?,'QUEUED',?,?)",
                id, name, hash, now, now);
        return find(id).orElseThrow();
    }
    public boolean transition(String id, String from, String to) {
        return jdbc.update("UPDATE kb_documents SET status=?,error=NULL,updated_at=? WHERE id=? AND status=?",
                to, OffsetDateTime.now(ZoneOffset.UTC), id, from) == 1;
    }
    public void markDeleting(String id) {
        jdbc.update("UPDATE kb_documents SET status='DELETING',error=NULL,updated_at=? WHERE id=?",
            OffsetDateTime.now(ZoneOffset.UTC), id);
    }
    public List<DocumentRecord> pending() {
        return jdbc.query("SELECT * FROM kb_documents WHERE status='QUEUED' OR (status='DELETING' AND (error IS NULL OR updated_at<?)) ORDER BY updated_at LIMIT 1",
            DOCUMENT, OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(30));
    }
    public void recoverInterrupted() {
        jdbc.update("UPDATE kb_documents SET status='QUEUED',error=NULL WHERE status='PROCESSING'");
    }
    @Transactional
    public void replaceChunks(String documentId, List<ChunkRecord> chunks) {
        jdbc.update("DELETE FROM kb_chunks WHERE document_id=?", documentId);
        jdbc.batchUpdate("INSERT INTO kb_chunks(id,document_id,chunk_index,content,page_number,section_title) VALUES(?,?,?,?,?,?)",
            chunks, 100, (ps, c) -> {
                ps.setString(1, c.id()); ps.setString(2, c.documentId()); ps.setInt(3, c.chunkIndex());
                ps.setString(4, c.text()); ps.setObject(5, c.page()); ps.setString(6, c.section());
            });
    }
    public void indexing(String id, String mode, String collection) {
        jdbc.update("UPDATE kb_documents SET index_mode=?,vector_collection=?,updated_at=? WHERE id=?",
            mode, collection, OffsetDateTime.now(ZoneOffset.UTC), id);
    }
    public void ready(String id, int count) {
        jdbc.update("UPDATE kb_documents SET status='READY',chunk_count=?,error=NULL,updated_at=? WHERE id=? AND status='PROCESSING'",
            count, OffsetDateTime.now(ZoneOffset.UTC), id);
    }
    public void failed(String id, String message) {
        jdbc.update("UPDATE kb_documents SET status='FAILED',error=?,updated_at=? WHERE id=? AND status='PROCESSING'",
            message, OffsetDateTime.now(ZoneOffset.UTC), id);
    }
    public void deletionFailed(String id) {
        jdbc.update("UPDATE kb_documents SET error=?,updated_at=? WHERE id=? AND status='DELETING'",
            "清理索引失败，将自动重试。请检查 Qdrant 服务和文件访问权限。", OffsetDateTime.now(ZoneOffset.UTC), id);
    }
    public List<ChunkRecord> chunks(String docId) {
        return jdbc.query("SELECT * FROM kb_chunks WHERE document_id=? ORDER BY chunk_index", CHUNK, docId);
    }
    public void eachReadyChunk(java.util.function.Consumer<ChunkRecord> consumer) {
        jdbc.query("SELECT c.* FROM kb_chunks c JOIN kb_documents d ON d.id=c.document_id WHERE d.status='READY'",
            rs -> { consumer.accept(CHUNK.mapRow(rs, 0)); });
    }
    public Map<String, SearchHit> readyHits(Collection<String> ids) {
        if (ids.isEmpty()) return Map.of();
        String marks = String.join(",", Collections.nCopies(ids.size(), "?"));
        var rows = jdbc.query("SELECT c.*,d.filename FROM kb_chunks c JOIN kb_documents d ON d.id=c.document_id " +
                "WHERE d.status='READY' AND c.id IN (" + marks + ")", (rs, n) -> hit(rs), ids.toArray());
        Map<String, SearchHit> result = new HashMap<>();
        rows.forEach(hit -> result.put(hit.id(), hit));
        return result;
    }
    private static SearchHit hit(ResultSet rs) throws SQLException {
        return new SearchHit(rs.getString("id"),rs.getString("document_id"),rs.getString("filename"),
            (Integer)rs.getObject("page_number"),rs.getString("section_title"),rs.getString("content"),0);
    }
    public long lexicalOnlyCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM kb_documents WHERE status='READY' AND index_mode='LEXICAL'", Long.class);
    }
    public long staleVectorCount(String collection) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM kb_documents WHERE status='READY' AND index_mode='HYBRID' AND vector_collection<>?",
            Long.class, collection);
    }
    public void remove(String id) { jdbc.update("DELETE FROM kb_documents WHERE id=?", id); }
}
