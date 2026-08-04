package com.aiagent.agent;

import com.aiagent.cache.SemanticCacheService;
import com.aiagent.config.AiProperties;
import com.aiagent.memory.LongContextManager;
import com.aiagent.metrics.PlatformMetricsService;
import com.aiagent.retrieval.MultiRecallService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AiAgentServiceAdditionalTest {
    @Mock private ChatLanguageModel chatLanguageModel;
    @Mock private StreamingChatLanguageModel streamingChatLanguageModel;
    @Mock private ReActAgent reActAgent;
    @Mock private SemanticCacheService semanticCacheService;
    @Mock private MultiRecallService multiRecallService;
    @Mock private LongContextManager longContextManager;
    @Mock private PlatformMetricsService metricsService;
    private AiAgentService service;

    @BeforeEach void setUp() {
        AiProperties props = new AiProperties();
        service = new AiAgentService(chatLanguageModel, streamingChatLanguageModel, props,
                reActAgent, semanticCacheService, multiRecallService, longContextManager, metricsService);
        org.mockito.Mockito.lenient().when(metricsService.startSample()).thenReturn(Timer.start());
    }

    @Test void shouldReturnCachedResponse() {
        when(semanticCacheService.getIfCached("q")).thenReturn("cached");
        assertEquals("cached", service.chat("s1", "q", false));
        verify(chatLanguageModel, never()).generate(anyString());
    }
    @Test void shouldCallModelWhenNoCache() {
        when(semanticCacheService.getIfCached(anyString())).thenReturn(null);
        when(chatLanguageModel.generate(anyString())).thenReturn("resp");
        when(longContextManager.getOptimizedContext(anyString(), anyString())).thenReturn("");
        when(multiRecallService.search(anyString(), anyInt())).thenReturn(Collections.emptyList());
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
        when(multiRecallService.search(anyString(), anyInt())).thenReturn(Collections.emptyList());
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
        verify(multiRecallService, never()).search(anyString(), anyInt());
    }
}
