package com.aiagent.customer_support.application;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CustomerSupportEvidence {
    private String evidenceId;
    private Long qaPairId;
    private Long documentId;
    private double score;
    private String category;
    private String question;
    private String retrievalSource;
}
