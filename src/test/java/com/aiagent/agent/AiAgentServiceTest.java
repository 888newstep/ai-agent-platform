package com.aiagent.agent;

import com.aiagent.cache.SemanticCacheService;
import com.aiagent.config.AiProperties;
import com.aiagent.document.RetrievalChunk;
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
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private MultiRecallService multiRecallService;

    @Mock
    private LongContextManager longContextManager;

    @Mock
    private PlatformMetricsService metricsService;

    private AiProperties aiProperties;
    private AiAgentService service;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        service = new AiAgentService(
                chatLanguageModel,
                streamingChatLanguageModel,
                aiProperties,
                reActAgent,
                semanticCacheService,
                multiRecallService,
                longContextManager,
                metricsService
        );
    }

    @Test
    void shouldReturnCachedAnswerWhenAvailable() {
        Timer.Sample sample = Timer.start();
        when(metricsService.startSample()).thenReturn(sample);
        when(semanticCacheService.getIfCached("你好")).thenReturn("缓存回答");

        String answer = service.chat("session-1", "你好", true);

        assertThat(answer).isEqualTo("缓存回答");
        verify(longContextManager).saveMessageAndMaybeSummarize("session-1", "user", "你好");
        verify(longContextManager).saveMessageAndMaybeSummarize("session-1", "assistant", "缓存回答");
        verify(metricsService).recordChat(eq("normal"), eq(true), eq(true), eq(true), any());
    }

    @Test
    void shouldGenerateNewAnswerWhenCacheMiss() {
        Timer.Sample sample = Timer.start();
        when(metricsService.startSample()).thenReturn(sample);
        when(semanticCacheService.getIfCached("问题")).thenReturn(null);
        when(longContextManager.getOptimizedContext("session-1", "问题")).thenReturn("历史上下文");
        when(multiRecallService.search("问题", 5)).thenReturn(List.of(
                RetrievalChunk.builder().id("doc-1").content("RAG内容").build()
        ));
        when(chatLanguageModel.generate(anyString())).thenReturn("新回答");

        String answer = service.chat("session-1", "问题", true);

        assertThat(answer).isEqualTo("新回答");
        verify(semanticCacheService).put("问题", "新回答");
        verify(longContextManager).saveMessageAndMaybeSummarize("session-1", "user", "问题");
        verify(longContextManager).saveMessageAndMaybeSummarize("session-1", "assistant", "新回答");
        verify(metricsService).recordChat(eq("normal"), eq(true), eq(false), eq(true), any());
    }

    @Test
    void shouldHandleReactChatWithCache() {
        Timer.Sample sample = Timer.start();
        when(metricsService.startSample()).thenReturn(sample);
        when(semanticCacheService.getIfCached("ReAct问题")).thenReturn("ReAct缓存");

        String answer = service.reactChat("session-1", "ReAct问题", true);

        assertThat(answer).isEqualTo("ReAct缓存");
        verify(metricsService).recordChat(eq("react"), eq(true), eq(true), eq(true), any());
    }

    @Test
    void shouldStreamChatFromCache() {
        Timer.Sample sample = Timer.start();
        when(metricsService.startSample()).thenReturn(sample);
        when(semanticCacheService.getIfCached("流式问题")).thenReturn("缓存流");

        Flux<String> stream = service.streamChat("session-1", "流式问题", true);

        List<String> results = stream.collectList().block();
        assertThat(results).containsExactly("缓存流");
    }

    @Test
    void shouldCreateAndClearSession() {
        String sessionId = service.createSession();
        assertThat(sessionId).isNotNull();

        service.clearSession(sessionId);
        verify(longContextManager).clearSession(sessionId);
    }
}
