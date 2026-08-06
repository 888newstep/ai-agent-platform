package com.aiagent.agent.application;

import com.aiagent.infrastructure.cache.SemanticCacheService;
import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.domain.RetrievalChunk;
import com.aiagent.infrastructure.memory.LongContextManager;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import com.aiagent.rag.application.MultiRecallService;
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
import java.util.List;
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
    private final AiProperties aiProperties;
    private final ReActAgent reActAgent;
    private final SemanticCacheService semanticCacheService;
    private final MultiRecallService multiRecallService;
    private final LongContextManager longContextManager;
    private final PlatformMetricsService metricsService;

    public String chat(String sessionId, String question, boolean useRag) {
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

            String context = useRag ? buildContextFromMultiRecall(question) : "";
            String optimizedHistory = longContextManager.getOptimizedContext(sessionId, question);
            String fullPrompt = buildPrompt(optimizedHistory, context, question);
            String response = chatLanguageModel.generate(fullPrompt);

            semanticCacheService.put(question, response);
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", response);
            success = true;
            return response;
        } finally {
            metricsService.recordChat("normal", useRag, cacheHit, success, sample);
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

            String context = useRag ? buildContextFromMultiRecall(question) : "";
            String optimizedHistory = longContextManager.getOptimizedContext(sessionId, question);
            String response = reActAgent.execute(question, context, optimizedHistory);

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

        String context = useRag ? buildContextFromMultiRecall(question) : "";
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
            log.error("流式 Chat 初始化失败", e);
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
        log.info("Create session: {}", sessionId);
        return sessionId;
    }

    public void clearSession(String sessionId) {
        longContextManager.clearSession(sessionId);
        log.info("Clear session: {}", sessionId);
    }

    private String buildContextFromMultiRecall(String question) {
        AiProperties.Rag ragConfig = aiProperties.getRag();
        List<RetrievalChunk> chunks = multiRecallService.search(question, ragConfig.getTopK());
        return buildContext(chunks);
    }

    private String buildContext(List<RetrievalChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Relevant reference information:\n");
        for (int i = 0; i < chunks.size(); i++) {
            RetrievalChunk chunk = chunks.get(i);
            sb.append("[").append(i + 1).append("] ").append(chunk.getContent()).append("\n");
        }
        return sb.toString();
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
