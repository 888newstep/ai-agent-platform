package com.aiagent.agent.application;

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
    private AiAgentService service;

    @BeforeEach void setUp() {
        service = new AiAgentService(chatLanguageModel, streamingChatLanguageModel,
                reActAgent, semanticCacheService, adaptiveRagService, longContextManager, metricsService);
        lenient().when(metricsService.startSample()).thenReturn(Timer.start());
    }

    @Test void shouldReturnCachedResponse() {
        when(semanticCacheService.getIfCached("q")).thenReturn("cached");
        assertEquals("cached", service.chat("s1", "q", false));
        verify(adaptiveRagService, never()).resolve(anyString());
    }

    @Test void shouldCallModelWhenNoCache() {
        when(semanticCacheService.getIfCached(anyString())).thenReturn(null);
        when(chatLanguageModel.generate(anyString())).thenReturn("resp");
        when(longContextManager.getOptimizedContext(anyString(), anyString())).thenReturn("");
        when(adaptiveRagService.resolve(anyString())).thenReturn(AdaptiveRagContext.builder().context("ctx").build());
        assertEquals("resp", service.chat("s1", "question", true));
    }

    @Test void shouldReturnCachedReactResponse() {
        when(semanticCacheService.getIfCached("q")).thenReturn("cached");
        assertEquals("cached", service.reactChat("s1", "q", false));
    }

    @Test void shouldCallReactAgent() {
        when(semanticCacheService.getIfCached(anyString())).thenReturn(null);
        when(reActAgent.execute(anyString(), anyString(), anyString())).thenReturn("react");
        when(longContextManager.getOptimizedContext(anyString(), anyString())).thenReturn("");
        when(adaptiveRagService.resolve(anyString())).thenReturn(AdaptiveRagContext.builder().context("ctx").build());
        assertEquals("react", service.reactChat("s1", "question", true));
    }

    @Test void shouldCreateSession() { assertNotNull(service.createSession()); }

    @Test void shouldClearSession() {
        assertDoesNotThrow(() -> service.clearSession("s1"));
        verify(longContextManager).clearSession("s1");
    }

    @Test void shouldChatWithoutRag() {
        when(semanticCacheService.getIfCached(anyString())).thenReturn(null);
        when(chatLanguageModel.generate(anyString())).thenReturn("no rag");
        when(longContextManager.getOptimizedContext(anyString(), anyString())).thenReturn("");
        assertEquals("no rag", service.chat("s1", "q", false));
        verify(adaptiveRagService, never()).resolve(anyString());
    }
}
