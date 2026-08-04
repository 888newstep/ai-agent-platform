package com.aiagent.config;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class FallbackStreamChatLanguageModel implements StreamingChatLanguageModel {
    private final StreamingChatLanguageModel primaryModel;
    private final StreamingChatLanguageModel fallbackModel;

    public FallbackStreamChatLanguageModel(StreamingChatLanguageModel primaryModel, StreamingChatLanguageModel fallbackModel) {
        this.primaryModel = primaryModel;
        this.fallbackModel = fallbackModel;
    }

    @Override
    public void generate(List<ChatMessage> messages, StreamingResponseHandler<AiMessage> handler) {
        primaryModel.generate(messages, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String token) {
                handler.onNext(token);
            }

            @Override
            public void onComplete(Response<AiMessage> response) {
                handler.onComplete(response);
            }

            @Override
            public void onError(Throwable throwable) {
                if (RateLimitDetector.isRateLimit(throwable)) {
                    log.warn("[MODEL-DEGRADE] streaming primaryModel={} fallbackModel={} reason=RateLimit error={}",
                            primaryModel.getClass().getSimpleName(),
                            fallbackModel.getClass().getSimpleName(),
                            throwable.getMessage());
                    fallbackModel.generate(messages, handler);
                } else {
                    handler.onError(throwable);
                }
            }
        });
    }
}
