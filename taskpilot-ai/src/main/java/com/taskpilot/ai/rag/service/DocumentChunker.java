package com.taskpilot.ai.rag.service;

import java.util.List;

public interface DocumentChunker {

    /**
     * Splits extracted document text into chunks using recursive text segmentation.
     *
     * @param text the full document text
     * @return ordered list of text chunks
     */
    List<String> chunkText(String text);
}
