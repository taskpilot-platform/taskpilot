package com.taskpilot.ai.rag.service;

import java.io.InputStream;

public interface DocumentTextExtractor {

    /**
     * Extracts plain text content from the provided input stream using Apache Tika.
     *
     * @param inputStream the document byte stream
     * @param filename    the original document filename (helps MIME detection)
     * @param contentType the declared content type (optional)
     * @return clean extracted text
     */
    String extractText(InputStream inputStream, String filename, String contentType);
}
