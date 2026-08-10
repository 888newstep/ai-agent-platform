package com.aiagent.agent.application;

import com.aiagent.infrastructure.cache.SemanticCacheService;
import com.aiagent.infrastructure.memory.LongContextManager;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import com.aiagent.rag.application.AdaptiveRagContext;
import com.aiagent.rag.application.AdaptiveRagService;
import com.aiagent.rag.application.RagRouteType;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAgentService {

    private static final String PROMPT_TEMPLATE =
            "You are an intelligent AI assistant. Answer the user based on the following information.\n\n"
                    + "Context information (if any):\n{{context}}\n\n"
                    + "User question: {{question}}\n\n"
                    + "Please answer in Chinese.";

    private final ChatLanguageModel chatLanguageModel;
    private final StreamingChatLanguageModel streamingChatLanguageModel;
    private final ReActAgent reActAgent;
    private final SemanticCacheService semanticCacheService;
    private final AdaptiveRagService adaptiveRagService;
    private final LongContextManager longContextManager;
    private final PlatformMetricsService metricsService;

    public ChatExecutionResult chatDetailed(String sessionId, String question, boolean useRag) {
        Timer.Sample sample = metricsService.startSample();
        boolean cacheHit = false;
        boolean success = false;

        try {
            String cached = semanticCacheService.getIfCached(question);
            if (cached != null) {
                cacheHit = true;
                longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
                longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", cached);
                success = true;
                return ChatExecutionResult.builder()
                        .answer(cached)
                        .adaptiveRagContext(null)
                        .cacheHit(true)
                        .responseSource("semantic_cache")
                        .build();
            }

            AdaptiveRagContext adaptiveContext = resolveAdaptiveContext(question, useRag);
            String optimizedHistory = longContextManager.getOptimizedContext(sessionId, question);
            String fullPrompt = buildPrompt(optimizedHistory, adaptiveContext.getContext(), question);
            String response = chatLanguageModel.generate(fullPrompt);

            semanticCacheService.put(question, response);
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", response);
            success = true;
            return ChatExecutionResult.builder()
                    .answer(response)
                    .adaptiveRagContext(adaptiveContext)
                    .cacheHit(false)
                    .responseSource(determineResponseSource(useRag, adaptiveContext))
                    .build();
        } finally {
            metricsService.recordChat("normal", useRag, cacheHit, success, sample);
        }
    }

    public String chat(String sessionId, String question, boolean useRag) {
        return chatDetailed(sessionId, question, useRag).getAnswer();
    }

    public ChatExecutionResult reactChatDetailed(String sessionId, String question, boolean useRag) {
        Timer.Sample sample = metricsService.startSample();
        boolean cacheHit = false;
        boolean success = false;

        try {
            String cached = semanticCacheService.getIfCached(question);
            if (cached != null) {
                cacheHit = true;
                longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
                longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", cached);
                success = true;
                return ChatExecutionResult.builder()
                        .answer(cached)
                        .adaptiveRagContext(null)
                        .cacheHit(true)
                        .responseSource("semantic_cache")
                        .reactTrace(null)
                        .build();
            }

            AdaptiveRagContext adaptiveContext = resolveAdaptiveContext(question, useRag);
            String optimizedHistory = longContextManager.getOptimizedContext(sessionId, question);
            ReActExecutionResult reactResult = reActAgent.executeDetailed(question, adaptiveContext.getContext(), optimizedHistory);
            String response = reactResult.getAnswer();

            semanticCacheService.put(question, response);
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", response);
            success = true;
            ChatExecutionResult result = ChatExecutionResult.builder()
                    .answer(response)
                    .adaptiveRagContext(adaptiveContext)
                    .cacheHit(false)
                    .responseSource(determineResponseSource(useRag, adaptiveContext))
                    .reactTrace(reactResult.getTrace())
                    .build();
            recordReActTraceMetrics(result.getReactTrace());
            return result;
        } finally {
            metricsService.recordChat("react", useRag, cacheHit, success, sample);
        }
    }

    public String reactChat(String sessionId, String question, boolean useRag) {
        Timer.Sample sample = metricsService.startSample();
        boolean cacheHit = false;
        boolean success = false;

        try {
            String cached = semanticCacheService.getIfCached(question);
            if (cached != null) {
                cacheHit = true;
                longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
                longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", cached);
                success = true;
                return cached;
            }

            AdaptiveRagContext adaptiveContext = resolveAdaptiveContext(question, useRag);
            String optimizedHistory = longContextManager.getOptimizedContext(sessionId, question);
            String response = reActAgent.execute(question, adaptiveContext.getContext(), optimizedHistory);

            semanticCacheService.put(question, response);
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", response);
            success = true;
            return response;
        } finally {
            metricsService.recordChat("react", useRag, cacheHit, success, sample);
        }
    }

    public Flux<String> streamChat(String sessionId, String question, boolean useRag) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        Timer.Sample sample = metricsService.startSample();

        String cached = semanticCacheService.getIfCached(question);
        if (cached != null) {
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", cached);
            sink.tryEmitNext(cached);
            sink.tryEmitComplete();
            metricsService.recordChat("stream", useRag, true, true, sample);
            return sink.asFlux();
        }

        String context = resolveAdaptiveContext(question, useRag).getContext();
        String optimizedHistory = longContextManager.getOptimizedContext(sessionId, question);
        String fullPrompt = buildPrompt(optimizedHistory, context, question);

        StringBuilder responseBuilder = new StringBuilder();
        AtomicBoolean metricsRecorded = new AtomicBoolean(false);

        TokenStream tokenStream;
        try {
            tokenStream = AiServices.builder(Assistant.class)
                    .streamingChatLanguageModel(streamingChatLanguageModel)
                    .build()
                    .chat(fullPrompt);
        } catch (Exception e) {
            log.error("Streaming chat initialization failed", e);
            metricsService.recordChat("stream", useRag, false, false, sample);
            sink.tryEmitError(e);
            return sink.asFlux()
                    .timeout(Duration.ofMinutes(5))
                    .onErrorResume(ex -> Flux.just("[Error: " + ex.getMessage() + "]"));
        }

        tokenStream
                .onNext(token -> {
                    sink.tryEmitNext(token);
                    responseBuilder.append(token);
                })
                .onComplete(response -> {
                    semanticCacheService.put(question, responseBuilder.toString());
                    longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
                    longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", responseBuilder.toString());
                    if (metricsRecorded.compareAndSet(false, true)) {
                        metricsService.recordChat("stream", useRag, false, true, sample);
                    }
                    sink.tryEmitComplete();
                })
                .onError(error -> {
                    log.error("Streaming chat error", error);
                    if (metricsRecorded.compareAndSet(false, true)) {
                        metricsService.recordChat("stream", useRag, false, false, sample);
                    }
                    sink.tryEmitError(error);
                })
                .start();

        return sink.asFlux()
                .timeout(Duration.ofMinutes(5))
                .onErrorResume(e -> Flux.just("[Error: " + e.getMessage() + "]"));
    }

    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        log.debug("Create session: {}", sessionId);
        return sessionId;
    }

    public void clearSession(String sessionId) {
        longContextManager.clearSession(sessionId);
        log.debug("Clear session: {}", sessionId);
    }

    public AdaptiveRagContext inspectAdaptiveRag(String question, boolean useRag) {
        return resolveAdaptiveContext(question, useRag);
    }

    private AdaptiveRagContext resolveAdaptiveContext(String question, boolean useRag) {
        return useRag ? adaptiveRagService.resolve(question) : AdaptiveRagContext.empty(question);
    }

    private void recordReActTraceMetrics(ReActExecutionTrace trace) {
        if (trace == null) {
            return;
        }
        boolean toolUsed = trace.getSteps() != null
                && trace.getSteps().stream().anyMatch(step -> step.getAction() != null && !step.getAction().isBlank());
        boolean toolError = trace.getSteps() != null
                && trace.getSteps().stream().anyMatch(step -> "tool_error".equals(step.getToolStatus()) || "unknown_tool".equals(step.getToolStatus()));
        Timer.Sample sample = metricsService.startSample();
        metricsService.recordReActTrace(trace.getStopReason(), trace.getStepCount(), toolUsed, toolError, trace.isCompleted(), sample);
    }
    private String determineResponseSource(boolean useRag, AdaptiveRagContext adaptiveContext) {
        if (!useRag || adaptiveContext == null || adaptiveContext.getRouteType() == null) {
            return "llm_direct";
        }
        if (!adaptiveContext.isUsedAdaptive()) {
            return "llm_direct";
        }
        if (adaptiveContext.getRouteType() == RagRouteType.DIRECT_ANSWER) {
            return "adaptive_direct_answer";
        }
        if (adaptiveContext.getChunkCount() > 0) {
            return "adaptive_rag";
        }
        return "adaptive_rag_no_evidence";
    }

    private String buildPrompt(String history, String context, String question) {
        Map<String, Object> variables = new java.util.HashMap<>();
        variables.put("context", context);
        variables.put("question", question);

        String basePrompt = PromptTemplate.from(PROMPT_TEMPLATE).apply(variables).text();
        if (!history.isEmpty()) {
            return "Conversation history:\n" + history + "\n\n" + basePrompt;
        }
        return basePrompt;
    }

    interface Assistant {
        TokenStream chat(String message);
    }
}