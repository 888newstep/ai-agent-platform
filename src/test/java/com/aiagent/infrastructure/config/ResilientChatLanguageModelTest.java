package com.aiagent.infrastructure.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ResilientChatLanguageModelTest {
    @Mock private ChatLanguageModel delegate;
    private CircuitBreaker circuitBreaker;
    private Retry retry;
    private ResilientChatLanguageModel model;

    @BeforeEach void setUp() {
        circuitBreaker = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(1))
                .slidingWindowSize(5).minimumNumberOfCalls(2).build()).circuitBreaker("t");
        retry = RetryRegistry.of(RetryConfig.custom().maxAttempts(2)
                .waitDuration(Duration.ofMillis(10)).build()).retry("t");
        model = new ResilientChatLanguageModel(delegate, circuitBreaker, retry);
    }

    @Test void shouldDelegate() {
        List<ChatMessage> msgs = List.of(UserMessage.from("hi"));
        Response<AiMessage> exp = Response.from(AiMessage.from("hello"));
        when(delegate.generate(msgs)).thenReturn(exp);
        assertEquals(exp, model.generate(msgs));
    }
    @Test void shouldRetryOnIOException() {
        List<ChatMessage> msgs = List.of(UserMessage.from("hi"));
        Response<AiMessage> exp = Response.from(AiMessage.from("hello"));
        when(delegate.generate(msgs)).thenThrow(new RuntimeException("t")).thenReturn(exp);
        assertEquals(exp, model.generate(msgs));
        verify(delegate, times(2)).generate(msgs);
    }
    @Test void shouldThrowAfterRetriesExhausted() {
        List<ChatMessage> msgs = List.of(UserMessage.from("hi"));
        when(delegate.generate(msgs)).thenThrow(new RuntimeException("err"));
        assertThrows(ResilientChatLanguageModel.LlmServiceUnavailableException.class, () -> model.generate(msgs));
    }
    @Test void shouldOpenCircuitOnFailures() {
        List<ChatMessage> msgs = List.of(UserMessage.from("hi"));
        when(delegate.generate(msgs)).thenThrow(new RuntimeException("fail"));
        for (int i = 0; i < 5; i++) { try { model.generate(msgs); } catch (Exception ignored) {} }
        assertEquals(CircuitBreaker.State.OPEN, circuitBreaker.getState());
    }
}
