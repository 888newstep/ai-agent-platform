package com.aiagent.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 文档 Chunk 明细表 — 追踪每个 chunk 的原文和向量 ID
 *
 * 设计说明：
 * - 支持从 MySQL 数据重建 Milvus 向量
 * - vector_id 关联 Milvus 中的向量记录，用于删除操作
 * - 通过 document_id 外键关联 documents 表
 */
@Data
@Entity
@Table(name = "document_chunks")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "char_count")
    @Builder.Default
    private Integer charCount = 0;

    @Column(name = "vector_id", length = 100)
    private String vectorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}