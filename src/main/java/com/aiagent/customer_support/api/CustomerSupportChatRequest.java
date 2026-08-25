package com.aiagent.customer_support.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CustomerSupportChatRequest(
        @NotBlank(message = "sessionId must not be blank")
        @Size(max = 100, message = "sessionId must not exceed 100 characters")
        String sessionId,

        @NotBlank(message = "question must not be blank")
        @Size(max = 20000, message = "question must not exceed 20000 characters")
        String question
) {
}
