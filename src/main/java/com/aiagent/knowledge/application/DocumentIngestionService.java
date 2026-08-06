package com.aiagent.knowledge.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.infrastructure.parser.DocumentParserFactory;
import com.aiagent.knowledge.infrastructure.splitter.TextSplitter;
import com.aiagent.knowledge.domain.Document;
import com.aiagent.knowledge.domain.DocumentProcessingStatus;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import com.aiagent.knowledge.infrastructure.repository.DocumentChunkRepository;
import com.aiagent.knowledge.infrastructure.repository.DocumentRepository;
import com.aiagent.knowledge.infrastructure.vectorstore.VectorStoreService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    @Async("taskExecutor")
    public void ingestAsync(Long documentId, String fileName, byte[] fileBytes) {
        var sample = metricsService.startSample();
        int chunkCount = 0;

        try {
            markProcessingStarted(documentId);
            try (InputStream inputStream = new ByteArrayInputStream(fileBytes)) {
                String content = parserFactory.parse(fileName, inputStream);
                chunkCount = processContent(fileName, content, documentId);
            }
            markProcessingCompleted(documentId, chunkCount);
            metricsService.recordDocumentIngestion("completed", chunkCount, sample);
        } catch (Exception e) {
            log.error("Failed to process document asynchronously [id={}]", documentId, e);
            markProcessingFailed(documentId, e);
            metricsService.recordDocumentIngestion("failed", chunkCount, sample);
        }
    }

    public int processContent(String fileName, String content, Long documentId) {
        AiProperties.Document docConfig = aiProperties.getDocument();
        List<TextSegment> segments = textSplitter.split(content, docConfig.getChunkSize(), docConfig.getChunkOverlap());

        for (int i = 0; i < segments.size(); i++) {
            TextSegment segment = segments.get(i);
            segment.metadata().put("fileName", fileName);
            segment.metadata().put("chunkIndex", i);
            segment.metadata().put("totalChunks", segments.size());
        }

        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        List<String> vectorIds = vectorStoreService.addAll(embeddings, segments);

        List<com.aiagent.knowledge.domain.DocumentChunk> chunks = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            chunks.add(com.aiagent.knowledge.domain.DocumentChunk.builder()
                    .documentId(documentId)
                    .chunkIndex(i)
                    .content(segments.get(i).text())
                    .charCount(segments.get(i).text().length())
                    .vectorId(i < vectorIds.size() ? vectorIds.get(i) : null)
                    .build());
        }
        documentChunkRepository.saveAll(chunks);

        log.info("Document processed asynchronously: {} chunks ({} vector records, {} db rows)",
                segments.size(), vectorIds.size(), chunks.size());
        return segments.size();
    }

    private void markProcessingStarted(Long documentId) {
        Document document = getDocument(documentId);
        document.setProcessingStatus(DocumentProcessingStatus.PROCESSING);
        document.setErrorMessage(null);
        document.setProcessingStartedAt(LocalDateTime.now());
        document.setProcessingCompletedAt(null);
        documentRepository.save(document);
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

    private Document getDocument(Long documentId) {
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
    }

    private String truncateError(Exception exception) {
        String message = exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName();
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
