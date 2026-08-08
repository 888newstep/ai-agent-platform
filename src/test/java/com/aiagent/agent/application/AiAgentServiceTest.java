package com.aiagent.agent.application;

import com.aiagent.infrastructure.cache.SemanticCacheService;
import com.aiagent.infrastructure.memory.LongContextManager;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import com.aiagent.rag.application.AdaptiveRagContext;
import com.aiagent.rag.application.AdaptiveRagService;
import com.aiagent.rag.application.RagRouteType;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiAgentServiceTest {

    @Mock
    private ChatLanguageModel chatLanguageModel;

    @Mock
    private StreamingChatLanguageModel streamingChatLanguageModel;

    @Mock
    private ReActAgent reActAgent;

    @Mock
    private SemanticCacheService semanticCacheService;

    @Mock
    private AdaptiveRagService adaptiveRagService;

    @Mock
    private LongContextManager longContextManager;

    @Mock
    private PlatformMetricsService metricsService;

    private AiAgentService service;

    @BeforeEach
    void setUp() {
        service = new AiAgentService(
                chatLanguageModel,
                streamingChatLanguageModel,
                reActAgent,
                semanticCacheService,
                adaptiveRagService,
                longContextManager,
                metricsService
        );
    }

    @Test
    void shouldReturnCachedAnswerWhenAvailable() {
        Timer.Sample sample = Timer.start();
        when(metricsService.startSample()).thenReturn(sample);
        when(semanticCacheService.getIfCached("hello")).thenReturn("cached answer");

        String answer = service.chat("session-1", "hello", true);

        assertThat(answer).isEqualTo("cached answer");
        verify(longContextManager).saveMessageAndMaybeSummarize("session-1", "user", "hello");
        verify(longContextManager).saveMessageAndMaybeSummarize("session-1", "assistant", "cached answer");
        verify(adaptiveRagService, never()).resolve(anyString());
        verify(metricsService).recordChat(eq("normal"), eq(true), eq(true), eq(true), any());
    }

    @Test
    void shouldGenerateNewAnswerWhenCacheMiss() {
        Timer.Sample sample = Timer.start();
        when(metricsService.startSample()).thenReturn(sample);
        when(semanticCacheService.getIfCached("question")).thenReturn(null);
        when(longContextManager.getOptimizedContext("session-1", "question")).thenReturn("history");
        when(adaptiveRagService.resolve("question")).thenReturn(AdaptiveRagContext.builder()
                .context("adaptive context")
                .build());
        when(chatLanguageModel.generate(anyString())).thenReturn("new answer");

        String answer = service.chat("session-1", "question", true);

        assertThat(answer).isEqualTo("new answer");
        verify(adaptiveRagService).resolve("question");
        verify(semanticCacheService).put("question", "new answer");
        verify(longContextManager).saveMessageAndMaybeSummarize("session-1", "user", "question");
        verify(longContextManager).saveMessageAndMaybeSummarize("session-1", "assistant", "new answer");
        verify(metricsService).recordChat(eq("normal"), eq(true), eq(false), eq(true), any());
    }

    @Test
    void shouldHandleReactChatWithCache() {
        Timer.Sample sample = Timer.start();
        when(metricsService.startSample()).thenReturn(sample);
        when(semanticCacheService.getIfCached("react question")).thenReturn("react cached");

        String answer = service.reactChat("session-1", "react question", true);

        assertThat(answer).isEqualTo("react cached");
        verify(metricsService).recordChat(eq("react"), eq(true), eq(true), eq(true), any());
    }

    @Test
    void shouldCallReactAgentWhenNoCache() {
        Timer.Sample sample = Timer.start();
        when(metricsService.startSample()).thenReturn(sample);
        when(semanticCacheService.getIfCached(anyString())).thenReturn(null);
        when(longContextManager.getOptimizedContext(anyString(), anyString())).thenReturn("");
        when(adaptiveRagService.resolve(anyString())).thenReturn(AdaptiveRagContext.builder().context("adaptive").build());
        when(reActAgent.execute(anyString(), anyString(), anyString())).thenReturn("react answer");

        String answer = service.reactChat("session-1", "question", true);

        assertThat(answer).isEqualTo("react answer");
        verify(adaptiveRagService).resolve("question");
        verify(reActAgent).execute("question", "adaptive", "");
    }

    @Test
    void shouldStreamChatFromCache() {
        Timer.Sample sample = Timer.start();
        when(metricsService.startSample()).thenReturn(sample);
        when(semanticCacheService.getIfCached("stream question")).thenReturn("stream cached");

        Flux<String> stream = service.streamChat("session-1", "stream question", true);

        List<String> results = stream.collectList().block();
        assertThat(results).containsExactly("stream cached");
    }

    @Test
    void shouldCreateAndClearSession() {
        String sessionId = service.createSession();
        assertThat(sessionId).isNotNull();

        service.clearSession(sessionId);
        verify(longContextManager).clearSession(sessionId);
    }

    @Test
    void shouldInspectAdaptiveRagWhenEnabled() {
        AdaptiveRagContext adaptiveContext = AdaptiveRagContext.builder()
                .originalQuery("refund question")
                .context("adaptive context")
                .usedAdaptive(true)
                .build();
        when(adaptiveRagService.resolve("refund question")).thenReturn(adaptiveContext);

        AdaptiveRagContext result = service.inspectAdaptiveRag("refund question", true);

        assertThat(result).isSameAs(adaptiveContext);
        verify(adaptiveRagService).resolve("refund question");
    }

    @Test
    void shouldReturnEmptyAdaptiveRagContextWhenDebugDisabled() {
        AdaptiveRagContext result = service.inspectAdaptiveRag("hello", false);

        assertThat(result.getOriginalQuery()).isEqualTo("hello");
        assertThat(result.getRouteType()).isEqualTo(RagRouteType.DIRECT_ANSWER);
        assertThat(result.isUsedAdaptive()).isFalse();
        verify(adaptiveRagService, never()).resolve(anyString());
    }

    @Test
    void shouldChatWithoutRag() {
        Timer.Sample sample = Timer.start();
        when(metricsService.startSample()).thenReturn(sample);
        when(semanticCacheService.getIfCached(anyString())).thenReturn(null);
        when(longContextManager.getOptimizedContext(anyString(), anyString())).thenReturn("");
        when(chatLanguageModel.generate(anyString())).thenReturn("no rag answer");

        String answer = service.chat("session-1", "q", false);

        assertThat(answer).isEqualTo("no rag answer");
        verify(adaptiveRagService, never()).resolve(anyString());
    }
}
