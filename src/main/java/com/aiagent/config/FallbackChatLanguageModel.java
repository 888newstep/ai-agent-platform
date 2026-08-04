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

    public FallbackChatLanguageModel(ChatLanguageModel primaryModel, ChatLanguageModel fallbackModel) {
        this.primaryModel = primaryModel;
        this.fallbackModel = fallbackModel;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        try {
            return primaryModel.generate(messages);
        } catch (Exception e) {
            if (RateLimitDetector.isRateLimit(e)) {
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
}
