package com.aiagent.knowledge.application;

import com.aiagent.knowledge.domain.Document;
import com.aiagent.knowledge.domain.RetrievalChunk;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import com.aiagent.knowledge.infrastructure.repository.DocumentRepository;
import com.aiagent.knowledge.infrastructure.vectorstore.VectorStoreService;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentService {

    private final EmbeddingModel embeddingModel;
    private final VectorStoreService vectorStoreService;
    private final DocumentRepository documentRepository;
    private final DocumentIngestionService documentIngestionService;
    private final PlatformMetricsService metricsService;

    public Document uploadDocument(MultipartFile file) {
        String fileName = StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "unnamed";
        log.info("Queueing document for async processing: {}", fileName);

        Document document = Document.builder()
                .fileName(fileName)
                .fileType(extractFileType(fileName))
                .fileSize(file.getSize())
                .chunkCount(0)
                .build();
        document = documentRepository.save(document);

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read uploaded file", e);
        }

        documentIngestionService.ingestAsync(document.getId(), fileName, fileBytes);
        metricsService.recordDocumentQueued();
        return document;
    }

    public Map<String, Object> getDocumentStatus(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("documentId", document.getId());
        response.put("fileName", document.getFileName());
        response.put("fileType", document.getFileType());
        response.put("fileSize", document.getFileSize());
        response.put("status", document.getProcessingStatus());
        response.put("chunkCount", document.getChunkCount());
        response.put("errorMessage", document.getErrorMessage());
        response.put("processingStartedAt", document.getProcessingStartedAt());
        response.put("processingCompletedAt", document.getProcessingCompletedAt());
        response.put("createdAt", document.getCreatedAt());
        response.put("updatedAt", document.getUpdatedAt());
        return response;
    }

    public List<RetrievalChunk> searchSimilar(String query, int topK, double threshold) {
        var queryEmbedding = embeddingModel.embed(query).content();
        List<dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment>> matches =
                vectorStoreService.search(queryEmbedding, topK, threshold);

        List<RetrievalChunk> chunks = new ArrayList<>();
        for (var match : matches) {
            chunks.add(RetrievalChunk.builder()
                    .id(match.embeddingId())
                    .content(match.embedded().text())
                    .score(match.score())
                    .metadata(match.embedded().metadata().toMap())
                    .build());
        }
        return chunks;
    }

    private static String extractFileType(String fileName) {
        if (!StringUtils.hasText(fileName) || !fileName.contains(".")) {
            return "unknown";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }
}
