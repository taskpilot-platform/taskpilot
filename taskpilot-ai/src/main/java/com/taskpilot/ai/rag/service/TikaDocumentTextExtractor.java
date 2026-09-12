package com.taskpilot.ai.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Slf4j
@Service
public class TikaDocumentTextExtractor implements DocumentTextExtractor {

    private final Tika tika;

    public TikaDocumentTextExtractor() {
        this.tika = new Tika();
    }

    @Override
    public String extractText(InputStream inputStream, String filename, String contentType) {
        if (inputStream == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
        try {
            Metadata metadata = new Metadata();
            if (filename != null && !filename.isBlank()) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            }
            if (contentType != null && !contentType.isBlank()) {
                metadata.set(Metadata.CONTENT_TYPE, contentType);
            }

            String extracted = tika.parseToString(inputStream, metadata);
            if (extracted == null) {
                return "";
            }
            return extracted.trim();
        } catch (Exception e) {
            log.error("Failed to extract text from document: filename={}, contentType={}, error={}",
                    filename, contentType, e.getMessage());
            throw new RuntimeException("Document text extraction failed: " + e.getMessage(), e);
        }
    }
}
