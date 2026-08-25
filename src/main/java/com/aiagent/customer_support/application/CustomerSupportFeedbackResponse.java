package com.aiagent.customer_support.application;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerSupportFeedbackResponse {
    private Long feedbackId;
    private String sessionId;
    private Long messageId;
    private Integer rating;
}
