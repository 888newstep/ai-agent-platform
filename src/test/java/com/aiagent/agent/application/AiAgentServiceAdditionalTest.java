package com.aiagent.agent.application;

import com.aiagent.chat.application.ChatSessionService;
import com.aiagent.infrastructure.cache.SemanticCacheService;
import com.aiagent.infrastructure.memory.LongContextManager;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import com.aiagent.rag.application.AdaptiveRagContext;
import com.aiagent.rag.application.AdaptiveRagService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAgentServiceAdditionalTest {
    @Mock private ChatLanguageModel chatLanguageModel;
    @Mock private StreamingChatLanguageModel streamingChatLanguageModel;
    @Mock private ReActAgent reActAgent;
    @Mock private SemanticCacheService semanticCacheService;
    @Mock private AdaptiveRagService adaptiveRagService;
    @Mock private LongContextManager longContextManager;
    @Mock private PlatformMetricsService metricsService;
    @Mock private ChatSessionService chatSessionService;
    private AiAgentService service;

    @BeforeEach void setUp() {
        service = new AiAgentService(chatLanguageModel, streamingChatLanguageModel,
                reActAgent, semanticCacheService, adaptiveRagService, longContextManager, metricsService,
                chatSessionService);
        lenient().when(metricsService.startSample()).thenReturn(Timer.start());
        lenient().when(chatSessionService.recordSuccessfulExchange(
                        anyString(), anyString(), anyString(), anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(5));
    }

    @Test void shouldReturnCachedResponse() {
        when(semanticCacheService.getIfCached("normal:direct", "q")).thenReturn("cached");
        assertEquals("cached", service.chat("user", "s1", "q", false));
        verify(adaptiveRagService, never()).resolve(anyString());
    }

    @Test void shouldCallModelWhenNoCache() {
        when(semanticCacheService.getIfCached(anyString(), anyString())).thenReturn(null);
        when(chatLanguageModel.generate(anyString())).thenReturn("resp");
        when(longContextManager.getOptimizedContext(anyString(), anyString())).thenReturn("");
        when(adaptiveRagService.resolve(anyString())).thenReturn(AdaptiveRagContext.builder().context("ctx").build());
        assertEquals("resp", service.chat("user", "s1", "question", true));
    }

    @Test void shouldReturnCachedReactResponse() {
        when(semanticCacheService.getIfCached("react:direct", "q")).thenReturn("cached");
        assertEquals("cached", service.reactChat("user", "s1", "q", false));
    }

    @Test void shouldCallReactAgent() {
        when(semanticCacheService.getIfCached(anyString(), anyString())).thenReturn(null);
        when(reActAgent.executeDetailed(anyString(), anyString(), anyString()))
                .thenReturn(ReActExecutionResult.builder().answer("react").build());
        when(longContextManager.getOptimizedContext(anyString(), anyString())).thenReturn("");
        when(adaptiveRagService.resolve(anyString())).thenReturn(AdaptiveRagContext.builder().context("ctx").build());
        assertEquals("react", service.reactChat("user", "s1", "question", true));
    }

    @Test void shouldCreateSession() {
        when(chatSessionService.createSession("user")).thenReturn("s1");
        assertNotNull(service.createSession("user"));
    }

    @Test void shouldClearSession() {
        assertDoesNotThrow(() -> service.clearSession("user", "s1"));
        verify(chatSessionService).deleteSession("user", "s1");
    }

    @Test void shouldChatWithoutRag() {
        when(semanticCacheService.getIfCached(anyString(), anyString())).thenReturn(null);
        when(chatLanguageModel.generate(anyString())).thenReturn("no rag");
        when(longContextManager.getOptimizedContext(anyString(), anyString())).thenReturn("");
        assertEquals("no rag", service.chat("user", "s1", "q", false));
        verify(adaptiveRagService, never()).resolve(anyString());
    }
}
