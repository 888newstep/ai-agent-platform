package com.aiagent.agent.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 视觉分析结果缓存表 — 避免重复调用多模态模型
 *
 * 设计说明：
 * - 同一张图片短时间内多次咨询时，直接复用缓存结果
 * - image_hash 为图片内容的 MD5 哈希
 * - model_name 区分不同模型的分析结果（doubao-mini / qwen3.7-flash）
 * - result_json 存储结构化分析结果（OCR、分类、表格提取等）
 * - Redis 也缓存一份（1h TTL），MySQL 做持久化兜底
 */
@Data
@Entity
@Table(name = "vision_analysis_cache")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisionAnalysisCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "image_hash", nullable = false, length = 64)
    private String imageHash;

    @Column(name = "model_name", nullable = false, length = 50)
    private String modelName;

    @Column(name = "result_json", nullable = false, columnDefinition = "json")
    private String resultJson;

    @Column(name = "token_used")
    private Integer tokenUsed;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}