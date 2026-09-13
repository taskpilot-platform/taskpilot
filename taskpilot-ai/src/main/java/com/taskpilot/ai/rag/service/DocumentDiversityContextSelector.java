package com.taskpilot.ai.rag.service;

import com.taskpilot.ai.rag.domain.DocumentIdAware;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies a two-pass soft-cap document diversity policy to vector search candidate pools.
 * Pass 1: Prioritizes document diversity by capping selections per documentId.
 * Pass 2: Backfills remaining context capacity from overflow candidates in ranked order.
 */
@Slf4j
@Component
public class DocumentDiversityContextSelector {

    public <T extends DocumentIdAware> List<T> selectProjectContext(
            List<T> candidates,
            int maxContext,
            int maxPerDocument
    ) {
        if (candidates == null || candidates.isEmpty() || maxContext <= 0) {
            return Collections.emptyList();
        }
        if (maxPerDocument <= 0) {
            maxPerDocument = 1;
        }

        Map<Long, Integer> documentCounts = new HashMap<>();
        List<T> selected = new ArrayList<>();
        List<T> overflow = new ArrayList<>();

        // Pass 1: Diversity priority
        for (T chunk : candidates) {
            Long docId = chunk.documentId();
            int count = documentCounts.getOrDefault(docId, 0);

            if (count < maxPerDocument && selected.size() < maxContext) {
                documentCounts.put(docId, count + 1);
                selected.add(chunk);
            } else {
                overflow.add(chunk);
            }

            if (selected.size() >= maxContext) {
                break;
            }
        }

        // Pass 2: Backfill if context has not reached maxContext
        if (selected.size() < maxContext) {
            for (T chunk : overflow) {
                if (selected.size() >= maxContext) {
                    break;
                }
                selected.add(chunk);
            }
        }

        log.debug("Document diversity context selection complete: candidates={}, selected={}, overflow={}, maxContext={}, maxPerDoc={}",
                candidates.size(), selected.size(), overflow.size(), maxContext, maxPerDocument);

        return selected;
    }
}
