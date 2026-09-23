package chatbot.chat.rag.ingest;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Paragraph-aware character windows. A window never crosses a source section or PDF page. */
@Component
public class Chunker {
    public static final int DEFAULT_SIZE = 800;
    public static final int DEFAULT_OVERLAP = 120;

    private final int size;
    private final int overlap;

    public Chunker() {
        this(DEFAULT_SIZE, DEFAULT_OVERLAP);
    }

    public Chunker(int size, int overlap) {
        if (size < 2 || overlap < 0 || overlap >= size) {
            throw new IllegalArgumentException("分块长度至少为2，重叠长度必须在0到分块长度之间");
        }
        this.size = size;
        this.overlap = overlap;
    }

    public List<TextChunk> split(List<ParsedSection> sections) {
        Objects.requireNonNull(sections, "sections");
        List<TextChunk> chunks = new ArrayList<>();
        for (ParsedSection section : sections) {
            Objects.requireNonNull(section, "section");
            if (section.text() == null || section.text().isBlank()) continue;
            String text = section.text().replace("\r\n", "\n").replace('\r', '\n').strip();
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(start + size, text.length());
                if (end < text.length()) {
                    if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
                    end = preferredBoundary(text, start, end);
                }
                String content = text.substring(start, end).strip();
                if (!content.isEmpty()) {
                    chunks.add(new TextChunk(chunks.size(), content, section.page(), section.section()));
                }
                if (end == text.length()) break;
                int next = end - overlap;
                // This also covers unusually large caller-supplied overlaps and surrogate pairs.
                if (next <= start) next = end;
                if (next < text.length() && Character.isLowSurrogate(text.charAt(next))) next++;
                start = next;
            }
        }
        return List.copyOf(chunks);
    }

    private int preferredBoundary(String text, int start, int end) {
        int minimum = Math.max(start + size / 2, start + overlap + 1);
        int paragraph = text.lastIndexOf("\n\n", end - 2);
        if (paragraph >= start && paragraph + 2 >= minimum) return paragraph + 2;
        int newline = text.lastIndexOf('\n', end - 1);
        if (newline >= start && newline + 1 >= minimum) return newline + 1;
        for (int i = end - 1; i >= minimum - 1; i--) {
            if ("。！？!?；;".indexOf(text.charAt(i)) >= 0) return i + 1;
        }
        for (int i = end - 1; i >= minimum - 1; i--) {
            if (Character.isWhitespace(text.charAt(i))) return i + 1;
        }
        return end;
    }
}
