package com.aiagent.knowledge.infrastructure.repository;

import com.aiagent.knowledge.domain.Document;
import com.aiagent.knowledge.domain.DocumentProcessingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByContentHash(String contentHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select document from Document document where document.id = :id")
    Optional<Document> findByIdForUpdate(@Param("id") Long id);

    List<Document> findByProcessingStatusInAndStagingPathIsNotNull(Collection<DocumentProcessingStatus> statuses);

    List<Document> findByProcessingStatusAndStagingPathIsNotNull(DocumentProcessingStatus status);

    long countByIdInAndProcessingStatus(Collection<Long> ids, DocumentProcessingStatus status);
}
