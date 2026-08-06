package com.aiagent.knowledge.infrastructure.repository;

import com.aiagent.knowledge.domain.Document;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, Long> {
}
