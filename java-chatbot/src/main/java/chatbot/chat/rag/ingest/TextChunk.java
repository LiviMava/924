package chatbot.chat.rag.ingest;

/** A zero-based chunk index and the source location carried through ingestion. */
public record TextChunk(int index, String text, Integer page, String section) {
}
