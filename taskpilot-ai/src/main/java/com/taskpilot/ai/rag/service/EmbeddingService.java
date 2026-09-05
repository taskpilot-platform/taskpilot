package com.taskpilot.ai.rag.service;

import java.util.List;

public interface EmbeddingService {

    /**
     * Converts a single text string into a vector embedding.
     *
     * @param text input text
     * @return float array vector of canonical dimensionality
     */
    float[] embedText(String text);

    /**
     * Converts a batch of text segments into vector embeddings.
     *
     * @param texts list of input texts
     * @return list of float array vectors
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * Returns the canonical embedding dimension (768).
     *
     * @return dimension count
     */
    int getDimension();
}
