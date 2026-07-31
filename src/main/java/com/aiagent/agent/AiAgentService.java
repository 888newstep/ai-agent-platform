package com.aiagent.agent;

import com.aiagent.cache.SemanticCacheService;
import com.aiagent.config.AiProperties;
import com.aiagent.document.DocumentChunk;
import com.aiagent.memory.LongContextManager;
import com.aiagent.retrieval.MultiRecallService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiAgentService {

    private final ChatLanguageModel chatLanguageModel;
    private final StreamingChatLanguageModel streamingChatLanguageModel;
    private final AiProperties aiProperties;
    private final ReActAgent reActAgent;
    private final SemanticCacheService semanticCacheService;
    private final MultiRecallService multiRecallService;
    private final LongContextManager longContextManager;

    private static final String PROMPT_TEMPLATE = """
            你是一个智能AI助手，请根据以下信息回答用户问题。
            
            上下文信息（如果有）：
            {{context}}
            
            用户问题：{{question}}
            
            请用中文回答。
            """;

    /**
     * 普通聊天（非 ReAct 模式）
     * 使用长上下文管理：滑动窗口 + 摘要压缩 + 历史检索（Q174）
     */
    public String chat(String sessionId, String question, boolean useRag) {
        // 1. 尝试语义缓存
        String cached = semanticCacheService.getIfCached(question);
        if (cached != null) {
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", cached);
            return cached;
        }

        // 2. 构建上下文（RAG 多路召回）
        String context = "";
        if (useRag) {
            context = buildContextFromMultiRecall(question);
        }

        // 3. 获取长上下文优化后的历史（滑动窗口 + 摘要检索）
        String optimizedHistory = longContextManager.getOptimizedContext(sessionId, question);
        String fullPrompt = buildPrompt(optimizedHistory, context, question);
        String response = chatLanguageModel.generate(fullPrompt);

        // 4. 写入语义缓存
        semanticCacheService.put(question, response);

        // 5. 保存会话（长上下文管理）
        longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
        longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", response);

        return response;
    }

    /**
     * ReAct 模式聊天（推理 + 工具调用循环）
     * 使用长上下文管理：滑动窗口 + 摘要压缩 + 历史检索（Q174）
     */
    public String reactChat(String sessionId, String question, boolean useRag) {
        // 1. 尝试语义缓存
        String cached = semanticCacheService.getIfCached(question);
        if (cached != null) {
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", cached);
            return cached;
        }

        // 2. 构建上下文
        String context = "";
        if (useRag) {
            context = buildContextFromMultiRecall(question);
        }

        // 3. 获取长上下文优化后的历史（滑动窗口 + 摘要检索）
        String optimizedHistory = longContextManager.getOptimizedContext(sessionId, question);
        String response = reActAgent.execute(question, context, optimizedHistory);

        // 4. 写入语义缓存
        semanticCacheService.put(question, response);

        // 5. 保存会话（长上下文管理）
        longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
        longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", response);

        return response;
    }

    /**
     * 流式聊天
     * 使用长上下文管理：滑动窗口 + 摘要压缩 + 历史检索（Q174）
     */
    public Flux<String> streamChat(String sessionId, String question, boolean useRag) {
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

        // 1. 尝试语义缓存
        String cached = semanticCacheService.getIfCached(question);
        if (cached != null) {
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
            longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", cached);
            sink.tryEmitNext(cached);
            sink.tryEmitComplete();
            return sink.asFlux();
        }

        // 2. 构建上下文（使用多路召回）
        String context = "";
        if (useRag) {
            context = buildContextFromMultiRecall(question);
        }

        // 3. 获取长上下文优化后的历史（滑动窗口 + 摘要检索）
        String optimizedHistory = longContextManager.getOptimizedContext(sessionId, question);
        String fullPrompt = buildPrompt(optimizedHistory, context, question);

        StringBuilder responseBuilder = new StringBuilder();

        TokenStream tokenStream = AiServices.builder(Assistant.class)
                .streamingChatLanguageModel(streamingChatLanguageModel)
                .build()
                .chat(fullPrompt);

        tokenStream
                .onNext(token -> {
                    sink.tryEmitNext(token);
                    responseBuilder.append(token);
                })
                .onComplete(response -> {
                    // 写入语义缓存
                    semanticCacheService.put(question, responseBuilder.toString());
                    // 保存会话（长上下文管理）
                    longContextManager.saveMessageAndMaybeSummarize(sessionId, "user", question);
                    longContextManager.saveMessageAndMaybeSummarize(sessionId, "assistant", responseBuilder.toString());
                    sink.tryEmitComplete();
                })
                .onError(error -> {
                    log.error("Streaming chat error", error);
                    sink.tryEmitError(error);
                })
                .start();

        return sink.asFlux()
                .timeout(Duration.ofMinutes(5))
                .onErrorResume(e -> Flux.just("[Error: " + e.getMessage() + "]"));
    }

    public String createSession() {
        String sessionId = UUID.randomUUID().toString();
        log.info("创建新会话: sessionId={}", sessionId);
        return sessionId;
    }

    public void clearSession(String sessionId) {
        longContextManager.clearSession(sessionId);
        log.info("清除会话: sessionId={}", sessionId);
    }

    /**
     * 使用多路召回构建上下文
     */
    private String buildContextFromMultiRecall(String question) {
        AiProperties.Rag ragConfig = aiProperties.getRag();
        List<DocumentChunk> chunks = multiRecallService.search(question, ragConfig.getTopK());
        return buildContext(chunks);
    }

    private String buildContext(List<DocumentChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是相关参考信息：\n");
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
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
            return "对话历史：\n" + history + "\n\n" + basePrompt;
        }
        return basePrompt;
    }

    interface Assistant {
        TokenStream chat(String message);
    }
}