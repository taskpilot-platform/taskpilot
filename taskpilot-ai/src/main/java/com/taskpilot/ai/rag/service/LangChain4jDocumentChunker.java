package com.taskpilot.ai.rag.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class LangChain4jDocumentChunker implements DocumentChunker {

    public static final int DEFAULT_MAX_SEGMENT_SIZE_IN_CHARS = 700;
    public static final int DEFAULT_MAX_OVERLAP_SIZE_IN_CHARS = 100;

    private final DocumentSplitter splitter;

    public LangChain4jDocumentChunker() {
        this(DEFAULT_MAX_SEGMENT_SIZE_IN_CHARS, DEFAULT_MAX_OVERLAP_SIZE_IN_CHARS);
    }

    public LangChain4jDocumentChunker(int maxSegmentSizeInChars, int maxOverlapSizeInChars) {
        this.splitter = DocumentSplitters.recursive(maxSegmentSizeInChars, maxOverlapSizeInChars);
    }

    @Override
    public List<String> chunkText(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        Document document = Document.from(text);
        List<TextSegment> segments = splitter.split(document);
        return segments.stream()
                .map(TextSegment::text)
                .filter(chunk -> chunk != null && !chunk.isBlank())
                .toList();
    }
}
