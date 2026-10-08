package com.taskpilot.ai.streaming.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
class StreamingChatEngineErrorClassificationTest {

    @Mock(answer = Answers.RETURNS_DEFAULTS)
    private StreamingChatEngine engine;

    @BeforeEach
    void setUp() {
        // Instantiate using mock dependencies or direct call
    }

    @Test
    @DisplayName("isNonRetryableModelError identifies 404 and model not found errors")
    void testIsNonRetryableModelError() {
        StreamingChatEngine chatEngine = new StreamingChatEngine(
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null
        );

        // 404 string in message
        Throwable error404 = new RuntimeException("404: The model `llama-3.3-70b-versatile` does not exist");
        assertTrue(chatEngine.isNonRetryableModelError(error404));

        // Model not found in cause chain
        Throwable nested = new RuntimeException("Outer failure", new IllegalStateException("model_not_found: meta-llama/llama-4-scout"));
        assertTrue(chatEngine.isNonRetryableModelError(nested));

        // Generic 500 error should remain retryable
        Throwable error500 = new RuntimeException("500: Internal Server Error");
        assertFalse(chatEngine.isNonRetryableModelError(error500));

        // Rate limit 429 error should remain retryable for key rotation
        Throwable error429 = new RuntimeException("429: Rate limit exceeded");
        assertFalse(chatEngine.isNonRetryableModelError(error429));
    }
}
