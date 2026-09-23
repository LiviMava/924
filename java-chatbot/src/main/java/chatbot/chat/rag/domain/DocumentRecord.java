package chatbot.chat.rag.domain;

import java.time.Instant;

public record DocumentRecord(String id, String filename, String sha256, String status,
                             int chunkCount, String indexMode, String vectorCollection,
                             String error, Instant createdAt, Instant updatedAt) {}
