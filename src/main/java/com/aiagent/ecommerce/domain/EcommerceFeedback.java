package com.aiagent.ecommerce.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户反馈表 — 评估 RAG 效果，持续优化知识库
 *
 * 设计说明：
 * - 记录完整调用链路（分类器选型 → 视觉模型 → 聚合模型）
 * - retrieved_qa 记录检索到的 QA 对，用于分析召回效果
 * - rating 1-5 分，反馈文本用于人工分析
 * - 数据驱动：分析分类器准确率、补充知识库盲区
 */
@Data
@Entity
@Table(name = "ecommerce_feedback")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EcommerceFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    @Column(name = "message_id")
    private Long messageId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(name = "retrieved_qa", columnDefinition = "json")
    private String retrievedQa;

    @Column(name = "model_chain", columnDefinition = "json")
    private String modelChain;

    @Column(name = "rating")
    private Integer rating;

    @Column(name = "feedback_text", length = 500)
    private String feedbackText;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
