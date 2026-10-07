package com.taskpilot.ai.streaming.testutil;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Controllable test-only StreamingChatModel without network access.
 * Captures request & handler, supports synchronous partial emissions, completions, and failures.
 */
public class ControllableStreamingChatModel implements StreamingChatModel {

    private final AtomicInteger invocationCount = new AtomicInteger(0);
    private volatile ChatRequest lastRequest;
    private volatile StreamingChatResponseHandler lastHandler;
    private volatile RuntimeException syncExceptionToThrow = null;

    public void setSyncExceptionToThrow(RuntimeException ex) {
        this.syncExceptionToThrow = ex;
    }

    @Override
    public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        invocationCount.incrementAndGet();
        this.lastRequest = chatRequest;
        this.lastHandler = handler;

        if (syncExceptionToThrow != null) {
            throw syncExceptionToThrow;
        }
    }

    public void emitPartial(String token) {
        if (lastHandler != null) {
            lastHandler.onPartialResponse(token);
        }
    }

    public void complete(ChatResponse response) {
        if (lastHandler != null) {
            lastHandler.onCompleteResponse(response);
        }
    }

    public void complete(String text) {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from(text))
                .tokenUsage(new TokenUsage(text.length() / 4, text.length() / 4, text.length() / 2))
                .build();
        complete(response);
    }

    public void completeEmpty() {
        ChatResponse response = ChatResponse.builder()
                .aiMessage(AiMessage.from(""))
                .build();
        complete(response);
    }

    public void fail(Throwable throwable) {
        if (lastHandler != null) {
            lastHandler.onError(throwable);
        }
    }

    public void remainSilent() {
        // Intentionally do nothing; model handler stays idle waiting for watchdog
    }

    public int getInvocationCount() {
        return invocationCount.get();
    }

    public ChatRequest getLastRequest() {
        return lastRequest;
    }

    public StreamingChatResponseHandler getLastHandler() {
        return lastHandler;
    }
}
