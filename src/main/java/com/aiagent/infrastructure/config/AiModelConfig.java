package com.aiagent.infrastructure.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiTokenizer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AiModelConfig {

    private final AiProperties aiProperties;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        AiProperties.Model modelConfig = aiProperties.getModel();
        ChatLanguageModel primary = switch (modelConfig.getProvider()) {
            case "deepseek" -> createDeepseekChatModel(modelConfig.getDeepseek());
            case "qianwen" -> createQianwenChatModel(modelConfig.getQianwen());
            case "doubao" -> createDoubaoChatModel(modelConfig.getDoubao());
            case "qwen3-flash" -> createQwen3FlashChatModel(modelConfig.getQwen3Flash());
            case "local" -> createLocalChatModel(modelConfig.getLocal());
            default -> createDeepseekChatModel(modelConfig.getDeepseek());
        };
        // 本地模型无需降级，直接返回
        if ("local".equals(modelConfig.getProvider())) {
            return primary;
        }
        // 非本地模型：主模型失败（限流）时自动降级到本地 Ollama 模型
        ChatLanguageModel fallback = createLocalChatModel(modelConfig.getLocal());
        log.info("ChatLanguageModel: primary={}, fallback={} (local)",
                modelConfig.getProvider(), modelConfig.getLocal().getModelName());
        ChatLanguageModel withFallback = new FallbackChatLanguageModel(primary, fallback);
        return new ResilientChatLanguageModel(
                withFallback,
                circuitBreakerRegistry.circuitBreaker("llmChat"),
                retryRegistry.retry("llmChat")
        );
    }

    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        AiProperties.Model modelConfig = aiProperties.getModel();
        StreamingChatLanguageModel primary = switch (modelConfig.getProvider()) {
            case "deepseek" -> createDeepseekStreamingModel(modelConfig.getDeepseek());
            case "qianwen" -> createQianwenStreamingModel(modelConfig.getQianwen());
            case "doubao" -> createDoubaoStreamingModel(modelConfig.getDoubao());
            case "qwen3-flash" -> createQwen3FlashStreamingModel(modelConfig.getQwen3Flash());
            case "local" -> createLocalStreamingModel(modelConfig.getLocal());
            default -> createDeepseekStreamingModel(modelConfig.getDeepseek());
        };
        // 本地模型无需降级，直接返回
        if ("local".equals(modelConfig.getProvider())) {
            return primary;
        }
        // 非本地模型：主模型失败（限流）时自动降级到本地 Ollama 模型
        StreamingChatLanguageModel fallback = createLocalStreamingModel(modelConfig.getLocal());
        log.info("StreamingChatLanguageModel: primary={}, fallback={} (local)",
                modelConfig.getProvider(), modelConfig.getLocal().getModelName());
        return new FallbackStreamChatLanguageModel(primary, fallback);
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        AiProperties.Embedding embeddingConfig = aiProperties.getEmbedding();
        return switch (embeddingConfig.getProvider()) {
            case "deepseek" -> createDeepseekEmbeddingModel(embeddingConfig.getDeepseek());
            case "qianwen" -> createQianwenEmbeddingModel(embeddingConfig.getQianwen());
            case "local" -> createLocalEmbeddingModel(embeddingConfig.getLocal());
            case "local-qwen3" -> createLocalEmbeddingModel(embeddingConfig.getLocalQwen3());
            case "siliconflow" -> createSiliconflowEmbeddingModel(embeddingConfig.getSiliconflow());
            default -> createDeepseekEmbeddingModel(embeddingConfig.getDeepseek());
        };
    }

    @Bean
    public OpenAiTokenizer tokenizer() {
        return new OpenAiTokenizer();
    }

    /**
     * 独立的 Doubao 聊天模型 Bean
     * 用于数据生成等场景，不受主 provider 切换影响
     */
    @Bean(name = "doubaoChatModel")
    public dev.langchain4j.model.chat.ChatLanguageModel doubaoChatModel() {
        return createDoubaoChatModel(aiProperties.getModel().getDoubao());
    }

    private ChatLanguageModel createDeepseekChatModel(AiProperties.Deepseek config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.7)
                .maxTokens(1024)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    private StreamingChatLanguageModel createDeepseekStreamingModel(AiProperties.Deepseek config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.7)
                .maxTokens(1024)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    private ChatLanguageModel createQianwenChatModel(AiProperties.Qianwen config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.7)
                .maxTokens(2048)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    private StreamingChatLanguageModel createQianwenStreamingModel(AiProperties.Qianwen config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.7)
                .maxTokens(2048)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    // =============================================
    // Doubao（豆包-火山引擎）— 低延迟分类器
    // =============================================

    private ChatLanguageModel createDoubaoChatModel(AiProperties.Doubao config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.3)        // 低温度，分类更确定
                .maxTokens(512)           // 小 token，快速响应
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    private StreamingChatLanguageModel createDoubaoStreamingModel(AiProperties.Doubao config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.3)
                .maxTokens(512)
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    // =============================================
    // Qwen3.7-Flash（阿里云百炼）— 多模态视觉
    // =============================================

    private ChatLanguageModel createQwen3FlashChatModel(AiProperties.Qwen3Flash config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.7)
                .maxTokens(4096)          // 大 token，支持图片分析
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private StreamingChatLanguageModel createQwen3FlashStreamingModel(AiProperties.Qwen3Flash config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.7)
                .maxTokens(4096)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private EmbeddingModel createDeepseekEmbeddingModel(AiProperties.DeepseekEmbedding config) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl("https://api.deepseek.com/v1")
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    private EmbeddingModel createQianwenEmbeddingModel(AiProperties.QianwenEmbedding config) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    private ChatLanguageModel createLocalChatModel(AiProperties.Local config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.7)
                .maxTokens(2048)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private StreamingChatLanguageModel createLocalStreamingModel(AiProperties.Local config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.7)
                .maxTokens(2048)
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private EmbeddingModel createLocalEmbeddingModel(AiProperties.LocalEmbedding config) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey("dummy")
                .modelName(config.getModelName())
                .dimensions(config.getDimension())
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    private EmbeddingModel createSiliconflowEmbeddingModel(AiProperties.SiliconflowEmbedding config) {
        return OpenAiEmbeddingModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                // bge-m3 固定输出 1024 维，不支持 dimensions 参数
                .timeout(Duration.ofSeconds(30))
                .build();
    }
}