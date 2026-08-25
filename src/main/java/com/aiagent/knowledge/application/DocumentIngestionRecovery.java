package com.aiagent.knowledge.application;

import com.aiagent.knowledge.domain.Document;
import com.aiagent.knowledge.domain.DocumentProcessingStatus;
import com.aiagent.knowledge.infrastructure.repository.DocumentRepository;
import com.aiagent.knowledge.infrastructure.storage.DocumentStagingStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentIngestionRecovery {

    private final DocumentRepository documentRepository;
    private final DocumentIngestionService ingestionService;
    private final DocumentStagingStorage stagingStorage;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedIngestion() {
        cleanupCompletedDocuments();

        List<Document> interruptedDocuments = documentRepository
                .findByProcessingStatusInAndStagingPathIsNotNull(
                        List.of(DocumentProcessingStatus.PENDING, DocumentProcessingStatus.PROCESSING));
        if (!interruptedDocuments.isEmpty()) {
            log.info("Recovering {} interrupted document ingestion task(s)", interruptedDocuments.size());
        }
        for (Document document : interruptedDocuments) {
            try {
                ingestionService.ingestAsync(document.getId());
            } catch (RuntimeException exception) {
                log.warn("Failed to schedule recovered document [id={}]: {}",
                        document.getId(), exception.getMessage());
            }
        }
    }

    private void cleanupCompletedDocuments() {
        List<Document> completedDocuments = documentRepository
                .findByProcessingStatusAndStagingPathIsNotNull(DocumentProcessingStatus.COMPLETED);
        for (Document document : completedDocuments) {
            try {
                stagingStorage.delete(document.getStagingPath());
                document.setStagingPath(null);
                documentRepository.save(document);
            } catch (Exception exception) {
                log.warn("Failed to clean completed document staging file [id={}]: {}",
                        document.getId(), exception.getMessage());
            }
        }
    }
}
