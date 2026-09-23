package chatbot.chat.rag.ingest;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkerTest {
    @Test
    void longChineseTextHasExactOverlapAndNoLostContent() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 2500; i++) source.append((char) ('一' + i));
        String text = source.toString();
        List<TextChunk> chunks = new Chunker().split(List.of(new ParsedSection(text, 3, "第3页")));

        assertEquals(4, chunks.size());
        StringBuilder reconstructed = new StringBuilder(chunks.get(0).text());
        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            assertEquals(i, chunk.index());
            assertEquals(3, chunk.page());
            assertTrue(chunk.text().length() <= 800);
            if (i == 0) continue;
            String previous = chunks.get(i - 1).text();
            assertEquals(previous.substring(previous.length() - 120), chunk.text().substring(0, 120));
            reconstructed.append(chunk.text().substring(120));
        }
        assertEquals(text, reconstructed.toString());
    }

    @Test
    void prefersParagraphBoundaryAndKeepsPageAndSectionBoundaries() {
        String text = "甲".repeat(55) + "\n\n" + "乙".repeat(40);
        List<TextChunk> chunks = new Chunker(80, 10).split(List.of(
                new ParsedSection(text, 1, "第一节"),
                new ParsedSection("第二页正文", 2, "第二节")));
        assertEquals("甲".repeat(55), chunks.get(0).text());
        assertEquals(3, chunks.size());
        assertEquals("第二页正文", chunks.get(2).text());
        assertEquals(2, chunks.get(2).page());
        assertEquals("第二节", chunks.get(2).section());
        assertFalse(chunks.get(1).text().contains("第二页"));
    }

    @Test
    void tinyWindowsAndLargeOverlapsTerminateAndKeepUnicodeIntact() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var chunks = new Chunker(2, 1).split(List.of(new ParsedSection("😀".repeat(1000), null, "正文")));
            assertEquals(1000, chunks.size());
            assertTrue(chunks.stream().allMatch(chunk -> chunk.text().equals("😀")));
        });
        var chunks = new Chunker(13, 4).split(List.of(new ParsedSection("😀".repeat(30), null, "正文")));
        for (TextChunk chunk : chunks) {
            assertFalse(Character.isLowSurrogate(chunk.text().charAt(0)));
            assertFalse(Character.isHighSurrogate(chunk.text().charAt(chunk.text().length() - 1)));
        }
    }

    @Test
    void emptyTextProducesNoChunksAndInvalidWindowSettingsFailFast() {
        assertTrue(new Chunker().split(List.of(new ParsedSection(" \n\t", null, "正文"))).isEmpty());
        assertTrue(new Chunker().split(List.of()).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Chunker(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new Chunker(10, 10));
        assertThrows(IllegalArgumentException.class, () -> new Chunker(10, -1));
    }
}
