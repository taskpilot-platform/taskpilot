package com.taskpilot.ai.streaming.sse;

import com.taskpilot.ai.service.ChatStreamStatusService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiSseTransportTest {

    private ChatStreamStatusService statusService;
    private AiSseTransport transport;

    @BeforeEach
    void setUp() {
        statusService = mock(ChatStreamStatusService.class);
        transport = new AiSseTransport(statusService);
    }

    @Test
    void testSafeSendCatchesIOException() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("Broken pipe")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        boolean sent = transport.safeSend(emitter, "test", "data", null);
        assertFalse(sent);
    }

    @Test
    void testSafeCompleteGuardsDoubleComplete() {
        SseEmitter emitter = mock(SseEmitter.class);
        AtomicBoolean completed = new AtomicBoolean(false);

        transport.safeComplete(emitter, completed);
        transport.safeComplete(emitter, completed);

        verify(emitter, times(1)).complete();
        assertTrue(completed.get());
    }

    @Test
    void testIsClientAbortRecognizesClientDisconnection() {
        IOException brokenPipe = new IOException("Broken pipe");
        assertTrue(transport.isClientAbort(brokenPipe));

        RuntimeException wrapped = new RuntimeException("Outer error", new IOException("connection reset by peer"));
        assertTrue(transport.isClientAbort(wrapped));

        assertFalse(transport.isClientAbort(new IllegalArgumentException("Invalid state")));
    }
}
