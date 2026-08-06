package com.aiagent.agent.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 消息分类日志表 — 多模型路由的决策记录
 *
 * 设计说明：
 * - 记录分类器的每一次决策，用于分析分类准确率
 * - classified_type: simple_vision(简单图片)/deep_vision(深度分析)/text_only(纯文本)
 * - routed_models 记录实际路由到的模型列表
 * - 数据驱动优化：根据分类日志调整分类器规则和阈值
 */
@Data
@Entity
@Table(name = "message_classify_log")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageClassifyLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    @Column(name = "question_hash", nullable = false, length = 64)
    private String questionHash;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "has_image")
    @Builder.Default
    private Boolean hasImage = false;

    @Column(name = "classified_type", nullable = false, length = 50)
    private String classifiedType;

    @Column(precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "classifier_model", length = 50)
    @Builder.Default
    private String classifierModel = "doubao-mini";

    @Column(name = "routed_models", columnDefinition = "json")
    private String routedModels;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}