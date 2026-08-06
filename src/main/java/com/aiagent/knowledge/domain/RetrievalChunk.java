package com.aiagent.knowledge.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * ???????
 * 
 * ?? RAG ??????????????????????
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
