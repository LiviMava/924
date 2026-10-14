package chatbot.chat.rag.service;

import chatbot.chat.rag.domain.*;
import chatbot.chat.rag.ingest.*;
import chatbot.chat.rag.retrieval.*;
import chatbot.chat.rag.store.DocumentRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class IngestionWorker {
    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);
    @org.springframework.beans.factory.annotation.Value("${rag.ingestion.batch-size:16}")
    private int embeddingBatchSize = 16;
    private final DocumentRepository repository;
    private final DocumentService documents;
    private final DocumentParser parser;
    private final Chunker chunker;
    private final LexicalIndex lexical;
    private final VectorIndex vectors;
    public IngestionWorker(DocumentRepository repository, DocumentService documents,
                           DocumentParser parser, Chunker chunker, LexicalIndex lexical, VectorIndex vectors) {
        this.repository=repository; this.documents=documents; this.parser=parser; this.chunker=chunker;
        this.lexical=lexical; this.vectors=vectors;
    }
    @PostConstruct void recover() { repository.recoverInterrupted(); }
    /** A durable DB queue, single worker. Repeated retries replace deterministic chunk IDs. */
    @Scheduled(fixedDelayString = "${rag.worker-delay-ms:1500}", initialDelayString = "${rag.worker-initial-delay-ms:1000}")
    public synchronized void tick() {
        for (var doc : repository.pending()) {
            if (doc.status().equals("DELETING")) { delete(doc); continue; }
            if (!repository.transition(doc.id(), "QUEUED", "PROCESSING")) continue;
            try {
                var parsed = parser.parse(documents.path(doc.id()), doc.filename());
                var split = chunker.split(parsed);
                if (split.isEmpty()) throw new IllegalArgumentException("未提取到可检索文本");
                List<ChunkRecord> chunks = split.stream().map(c -> new ChunkRecord(
                    UUID.nameUUIDFromBytes((doc.id()+":"+c.index()).getBytes(StandardCharsets.UTF_8)).toString(),
                    doc.id(), c.index(), c.text(), c.page(), c.section())).toList();
                vectors.deleteDocument(doc.vectorCollection(), doc.id());
                lexical.delete(doc.id());
                repository.replaceChunks(doc.id(), chunks);
                boolean hybrid = vectors.configured();
                repository.indexing(doc.id(), hybrid ? "HYBRID" : "LEXICAL", hybrid ? vectors.collection() : null);
                if (hybrid) {
                    int batchSize = Math.max(1, Math.min(64, embeddingBatchSize));
                    for (int i=0; i<chunks.size(); i+=batchSize) {
                        if (!processing(doc.id())) break;
                        vectors.add(chunks.subList(i, Math.min(i+batchSize, chunks.size())));
                    }
                }
                if (!processing(doc.id())) continue;
                lexical.replace(doc.id(), chunks);
                repository.ready(doc.id(), chunks.size());
                log.info("document_indexed documentId={} chunks={} mode={}",doc.id(),chunks.size(),hybrid?"HYBRID":"LEXICAL");
            } catch (Exception e) {
                String detail = e instanceof IllegalArgumentException ? e.getMessage() :
                    "入库失败，请检查模型配置、向量维度、Qdrant 服务和文件权限后重试";
                if (detail == null) detail = "文档处理失败";
                repository.failed(doc.id(), detail.substring(0, Math.min(900, detail.length())));
                log.warn("document_failed documentId={} errorType={}", doc.id(), e.getClass().getSimpleName());
            }
        }
    }
    private boolean processing(String id) {
        return repository.find(id).map(d -> d.status().equals("PROCESSING")).orElse(false);
    }
    private void delete(DocumentRecord doc) {
        try {
            vectors.deleteDocument(doc.vectorCollection(),doc.id());
            lexical.delete(doc.id());
            Files.deleteIfExists(documents.path(doc.id()));
            repository.remove(doc.id());
            log.info("document_deleted documentId={}", doc.id());
        } catch (Exception e) {
            repository.deletionFailed(doc.id());
            log.warn("document_delete_pending documentId={} errorType={}",doc.id(),e.getClass().getSimpleName());
        }
    }
}
