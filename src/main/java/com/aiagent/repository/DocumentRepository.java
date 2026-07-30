package com.aiagent.repository;

import com.aiagent.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentRepository extends JpaRepository<Document, Long> {
    List<Document> findByUploadedByOrderByCreatedAtDesc(Long uploadedBy);
    List<Document> findByFileTypeOrderByCreatedAtDesc(String fileType);
}
