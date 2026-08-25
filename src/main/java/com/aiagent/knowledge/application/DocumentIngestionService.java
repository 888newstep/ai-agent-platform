package com.aiagent.knowledge.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.infrastructure.parser.DocumentParserFactory;
import com.aiagent.knowledge.infrastructure.splitter.TextSplitter;
import com.aiagent.knowledge.domain.Document;
import com.aiagent.knowledge.domain.DocumentProcessingStatus;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import com.aiagent.knowledge.infrastructure.repository.DocumentChunkRepository;
import com.aiagent.knowledge.infrastructure.repository.DocumentRepository;
import com.aiagent.knowledge.infrastructure.storage.DocumentStagingStorage;
import com.aiagent.knowledge.infrastructure.vectorstore.VectorStoreService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIngestionService {

    private final DocumentParserFactory parserFactory;
    private final TextSplitter textSplitter;
    private final EmbeddingModel embeddingModel;
    private final VectorStoreService vectorStoreService;
    private final AiProperties aiProperties;
    private final DocumentRepository documentRepository;
    private final DocumentChunkRepository documentChunkRepository;
    private final PlatformMetricsService metricsService;
    private final DocumentStagingStorage stagingStorage;
    private final Set<Long> inFlightDocumentIds = ConcurrentHashMap.newKeySet();

    @Async("documentIngestionExecutor")
    public void ingestAsync(Long documentId) {
        if (!inFlightDocumentIds.add(documentId)) {
            log.info("Skipping duplicate in-flight document ingestion [id={}]", documentId);
            return;
        }

        var sample = metricsService.startSample();
        int chunkCount = 0;
        String stagingPath = null;
        boolean completed = false;

        try {
            Document document = markProcessingStarted(documentId);
            stagingPath = document.getStagingPath();
            if (stagingPath == null || stagingPath.isBlank()) {
                throw new IllegalStateException("Document staging path is missing");
            }
            removePreviouslyPersistedChunks(documentId);
            try (InputStream inputStream = stagingStorage.open(stagingPath)) {
                String content = parserFactory.parse(document.getFileName(), inputStream);
                chunkCount = processContent(document.getFileName(), content, documentId);
            }
            markProcessingCompleted(documentId, chunkCount);
            completed = true;
            metricsService.recordDocumentIngestion("completed", chunkCount, sample);
        } catch (Exception e) {
            log.error("Failed to process document asynchronously [id={}]", documentId, e);
            markProcessingFailed(documentId, e);
            metricsService.recordDocumentIngestion("failed", chunkCount, sample);
        } finally {
            if (completed) {
                cleanupCompletedStaging(documentId, stagingPath);
            }
            inFlightDocumentIds.remove(documentId);
        }
    }

    public int processContent(String fileName, String content, Long documentId) {
        AiProperties.Document docConfig = aiProperties.getDocument();
        List<TextSegment> segments = textSplitter.split(content, docConfig.getChunkSize(), docConfig.getChunkOverlap());
        if (segments.isEmpty()) {
            throw new IllegalStateException("Document parser produced no indexable content");
        }

        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            segment.metadata().put("documentId", documentId);
            segment.metadata().put("fileName", fileName);
            segment.metadata().put("chunkIndex", i);
            segment.metadata().put("totalChunks", segments.size());
        }

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        if (embeddings.size() != segments.size()) {
            throw new IllegalStateException("Embedding count does not match document chunk count");
        }
        List<String> vectorIds = vectorStoreService.addAll(embeddings, segments);
        if (vectorIds.size() != segments.size() || vectorIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            removeVectorsQuietly(vectorIds);
            throw new IllegalStateException("Vector store did not return an id for every document chunk");
        }

        List<com.aiagent.knowledge.domain.DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            chunks.add(com.aiagent.knowledge.domain.DocumentChunk.builder()
                    .documentId(documentId)
                    .chunkIndex(i)
                    .content(segments.get(i).text())
                    .charCount(segments.get(i).text().length())
                    .vectorId(vectorIds.get(i))
                    .build());
        }
        try {
            documentChunkRepository.saveAll(chunks);
        } catch (RuntimeException exception) {
            removeVectorsQuietly(vectorIds);
            throw exception;
        }

        log.info("Document processed asynchronously: {} chunks ({} vector records, {} db rows)",
                segments.size(), vectorIds.size(), chunks.size());
        return segments.size();
    }

    private Document markProcessingStarted(Long documentId) {
        Document document = getDocument(documentId);
        document.setProcessingStatus(DocumentProcessingStatus.PROCESSING);
        document.setErrorMessage(null);
        document.setProcessingStartedAt(LocalDateTime.now());
        document.setProcessingCompletedAt(null);
        return documentRepository.save(document);
    }

    private void markProcessingCompleted(Long documentId, int chunkCount) {
        Document document = getDocument(documentId);
        document.setProcessingStatus(DocumentProcessingStatus.COMPLETED);
        document.setChunkCount(chunkCount);
        document.setErrorMessage(null);
        document.setProcessingCompletedAt(LocalDateTime.now());
        documentRepository.save(document);
    }

    private void markProcessingFailed(Long documentId, Exception exception) {
        Document document = getDocument(documentId);
        document.setProcessingStatus(DocumentProcessingStatus.FAILED);
        document.setErrorMessage(truncateError(exception));
        document.setProcessingCompletedAt(LocalDateTime.now());
        documentRepository.save(document);
    }

    private void removePreviouslyPersistedChunks(Long documentId) {
        List<com.aiagent.knowledge.domain.DocumentChunk> existingChunks =
                documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);
        for (com.aiagent.knowledge.domain.DocumentChunk chunk : existingChunks) {
            if (chunk.getVectorId() != null && !chunk.getVectorId().isBlank()) {
                vectorStoreService.remove(chunk.getVectorId());
            }
        }
        if (!existingChunks.isEmpty()) {
            documentChunkRepository.deleteByDocumentId(documentId);
        }
    }

    private void removeVectorsQuietly(List<String> vectorIds) {
        for (String vectorId : vectorIds) {
            if (vectorId == null || vectorId.isBlank()) {
                continue;
            }
            try {
                vectorStoreService.remove(vectorId);
            } catch (RuntimeException cleanupException) {
                log.warn("Failed to roll back vector [{}]: {}", vectorId, cleanupException.getMessage());
            }
        }
    }

    private void cleanupCompletedStaging(Long documentId, String stagingPath) {
        try {
            stagingStorage.delete(stagingPath);
            Document document = getDocument(documentId);
            if (document.getProcessingStatus() == DocumentProcessingStatus.COMPLETED
                    && stagingPath.equals(document.getStagingPath())) {
                document.setStagingPath(null);
                documentRepository.save(document);
            }
        } catch (Exception cleanupException) {
            log.warn("Document [{}] completed but staging cleanup will be retried on startup: {}",
                    documentId, cleanupException.getMessage());
        }
    }

    private Document getDocument(Long documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
    }

    private String truncateError(Exception exception) {
        String message = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
