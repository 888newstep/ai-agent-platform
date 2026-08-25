package com.aiagent.ecommerce.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 电商客服 QA 知识库源数据表
 *
 * 设计说明：
 * - MySQL 存完整 QA 记录（源数据）
 * - Milvus 存向量（仅用于检索）
 * - 从 MySQL 可完全重建 Milvus 向量
 * - hit_count 和 last_hit_at 用于统计高频知识
 * - status 支持软删除，不删除 Milvus 中对应向量
 */
@Data
@Entity
@Table(name = "ecommerce_qa_pairs")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EcommerceQaPair {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 1024)
    private String question;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(name = "qa_text", columnDefinition = "TEXT")
    private String qaText;

    @Column(length = 100)
    private String category;

    @Column(name = "source_file", length = 255)
    private String sourceFile;

    @Column(name = "record_hash", length = 64, unique = true)
    private String recordHash;

    @Column(name = "vector_id", length = 100)
    private String vectorId;

    @Column(name = "status")
    @Builder.Default
    private Integer status = 1;

    @Column(name = "hit_count")
    @Builder.Default
    private Integer hitCount = 0;

    @Column(name = "last_hit_at")
    private LocalDateTime lastHitAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
