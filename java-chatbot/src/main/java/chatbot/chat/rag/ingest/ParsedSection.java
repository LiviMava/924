package chatbot.chat.rag.ingest;

/** Extracted text with its original location. PDF page numbers are one-based. */
public record ParsedSection(String text, Integer page, String section) {
}
