package com.aiagent.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息明细表 — 持久化所有对话历史
 *
 * 设计说明：
 * - MySQL 做全量持久化，Redis 做热缓存（最近 100 条）
 * - model_chain 记录多模型调用链路（如分类器→视觉模型→聚合模型）
 * - rag_chunks 记录检索到的 chunk 信息，用于排查 RAG 效果
 */
@Data
@Entity
@Table(name = "messages")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 100)
    private String sessionId;

    @Column(nullable = false, length = 20)
    private String role;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "msg_type", length = 30)
    @Builder.Default
    private String msgType = "text";

    @Column(name = "model_chain", columnDefinition = "json")
    private String modelChain;

    @Column(name = "rag_chunks", columnDefinition = "json")
    private String ragChunks;

    @Column(name = "tokens_used")
    private Integer tokensUsed;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}