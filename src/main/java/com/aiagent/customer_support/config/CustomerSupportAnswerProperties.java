package com.aiagent.customer_support.config;

import com.aiagent.rag.application.RagVerificationLevel;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "cs.answer")
public class CustomerSupportAnswerProperties {

    private RagVerificationLevel minimumVerificationLevel = RagVerificationLevel.HIGH;
    private String handoffMessage = "当前知识库没有足够信息可以可靠回答，建议转人工客服进一步处理。";
    private int maxEvidence = 5;
}
