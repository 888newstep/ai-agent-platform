package com.aiagent.chat.application;

public record ChatExchangeMetadata(
        String messageType,
        String modelChain,
        String ragChunks
) {
    public static ChatExchangeMetadata empty() {
        return new ChatExchangeMetadata("text", null, null);
    }
}
