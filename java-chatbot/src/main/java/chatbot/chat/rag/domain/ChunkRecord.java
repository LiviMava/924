package chatbot.chat.rag.domain;

public record ChunkRecord(String id, String documentId, int chunkIndex, String text,
                          Integer page, String section) {}
