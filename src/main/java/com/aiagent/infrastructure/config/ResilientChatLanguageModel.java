package com.aiagent.infrastructure.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class ResilientChatLanguageModel implements ChatLanguageModel {

    private final ChatLanguageModel delegate;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public ResilientChatLanguageModel(ChatLanguageModel delegate,
                                       CircuitBreaker circuitBreaker,
                                       Retry retry) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        try {
            return retry.executeSupplier(() ->
                    circuitBreaker.executeSupplier(() -> delegate.generate(messages))
            );
        } catch (CallNotPermittedException e) {
            log.warn("Circuit breaker OPEN, LLM service unavailable. State: {}", circuitBreaker.getState());
            throw new LlmServiceUnavailableException("LLM service is temporarily unavailable (circuit breaker open)", e);
        } catch (Exception e) {
            log.error("LLM call failed after retries: {}", e.getMessage());
            throw new LlmServiceUnavailableException("LLM call failed: " + e.getMessage(), e);
        }
    }

    public static class LlmServiceUnavailableException extends RuntimeException {
        public LlmServiceUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
