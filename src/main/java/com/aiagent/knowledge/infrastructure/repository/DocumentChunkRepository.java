package com.aiagent.knowledge.infrastructure.repository;

import com.aiagent.knowledge.domain.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {
    List<DocumentChunk> findByDocumentIdOrderByChunkIndexAsc(Long documentId);
    void deleteByDocumentId(Long documentId);
    long countByDocumentId(Long documentId);
}