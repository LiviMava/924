package chatbot.chat.rag.domain;

/** id is a stable chunk UUID, citation number is its 1-based position in the returned list. */
public record SearchHit(String id, String documentId, String filename, Integer page,
                        String section, String text, double score) {
    public SearchHit withScore(double newScore) {
        return new SearchHit(id, documentId, filename, page, section, text, newScore);
    }
}
