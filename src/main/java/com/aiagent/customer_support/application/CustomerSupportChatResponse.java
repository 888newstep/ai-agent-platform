package com.aiagent.customer_support.application;

import com.aiagent.rag.application.RagVerificationLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerSupportChatResponse {
    private String sessionId;
    private Long messageId;
    private String answer;
    private String resolution;
    private boolean handoffSuggested;
    private String responseSource;
    private RagVerificationLevel verificationLevel;
    private boolean answerSupported;
    private double answerSupportScore;
    private String answerSupportReason;
    private List<CustomerSupportEvidence> evidence;
}
