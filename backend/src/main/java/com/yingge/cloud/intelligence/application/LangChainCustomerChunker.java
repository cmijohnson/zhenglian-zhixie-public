package com.yingge.cloud.intelligence.application;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LangChainCustomerChunker {
    private static final int MAX_CHARS = 1_200;
    private static final int OVERLAP_CHARS = 120;

    public List<TextSegment> split(String text) {
        String normalized = text == null ? "" : text.strip();
        if (normalized.isBlank()) return List.of();
        Document document = Document.from(normalized);
        List<TextSegment> segments = new ArrayList<>();
        int start = 0;
        while (start < document.text().length()) {
            int desiredEnd = Math.min(document.text().length(), start + MAX_CHARS);
            int end = boundary(document.text(), start, desiredEnd);
            String chunk = document.text().substring(start, end).strip();
            if (!chunk.isBlank()) segments.add(TextSegment.from(chunk));
            if (end >= document.text().length()) break;
            start = Math.max(start + 1, end - OVERLAP_CHARS);
        }
        return List.copyOf(segments);
    }

    private int boundary(String value, int start, int desiredEnd) {
        if (desiredEnd >= value.length()) return value.length();
        int minimum = Math.min(desiredEnd, start + MAX_CHARS / 2);
        for (int index = desiredEnd; index >= minimum; index--) {
            char character = value.charAt(index - 1);
            if (character == '\n' || character == '。' || character == '！'
                    || character == '？' || character == ';' || character == '；') {
                return index;
            }
        }
        return desiredEnd;
    }
}
