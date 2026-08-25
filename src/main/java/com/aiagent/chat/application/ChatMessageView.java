package com.aiagent.chat.application;

import java.time.LocalDateTime;

public record ChatMessageView(
        Long id,
        String role,
        String content,
        String messageType,
        LocalDateTime createdAt
) {
}
