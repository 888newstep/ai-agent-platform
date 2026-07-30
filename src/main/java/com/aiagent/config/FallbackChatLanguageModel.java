package com.aiagent.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
class FallbackChatLanguageModel implements ChatLanguageModel {
    private final ChatLanguageModel primaryModel;
    private final ChatLanguageModel fallbackModel;

    static final List<String> RATE_LIMIT_KEYWORDS = List.of(
            "429",
            "rate limit",
            "too many requests",
            "throttling",
            "rate_limit_exceeded",
            "限流",
            "请求频率过高"
    );

    public FallbackChatLanguageModel(ChatLanguageModel primaryModel, ChatLanguageModel fallbackModel) {
        this.primaryModel = primaryModel;
        this.fallbackModel = fallbackModel;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        try {
            return primaryModel.generate(messages);
        } catch (Exception e) {
            if(isRateLimit(e)) {
                log.warn("[MODEL-DEGRADE] primaryModel={} fallbackModel={} reason=RateLimit error={}",
                        primaryModel.getClass().getSimpleName(),
                        fallbackModel.getClass().getSimpleName(),
                        e.getMessage());
                return fallbackModel.generate(messages);
            }
            log.warn("[MODEL-FAILED] primaryModel={} reason=NotRateLimit error={}",
                    primaryModel.getClass().getSimpleName(),
                    e.getMessage());
            throw e;
        }
    }

    boolean isRateLimit(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            String msg = cause.getMessage() == null ? "" : cause.getMessage().toLowerCase();
            if(RATE_LIMIT_KEYWORDS.stream().anyMatch(msg::contains)){
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
