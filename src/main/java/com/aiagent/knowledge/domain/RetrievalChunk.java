package com.aiagent.knowledge.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * RAG 检索结果片段。
 *
 * 统一承载检索结果的标识、正文、相关性分数和元数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RetrievalChunk {
    private String id;
    private String content;
    private double score;
    private Map<String, Object> metadata;
}
