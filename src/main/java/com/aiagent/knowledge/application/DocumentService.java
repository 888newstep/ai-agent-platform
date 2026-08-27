package com.aiagent.knowledge.application;

import com.aiagent.knowledge.domain.Document;
import com.aiagent.knowledge.domain.DocumentProcessingStatus;
import com.aiagent.knowledge.domain.RetrievalChunk;
import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.infrastructure.idempotency.IdempotencyService;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import com.aiagent.knowledge.infrastructure.repository.DocumentRepository;
import com.aiagent.knowledge.infrastructure.parser.DocumentParserFactory;
import com.aiagent.knowledge.infrastructure.storage.DocumentStagingStorage;
import com.aiagent.knowledge.infrastructure.storage.DocumentStagingStorage.StagedDocument;
import com.aiagent.knowledge.infrastructure.vectorstore.VectorStoreService;
import com.aiagent.knowledge.infrastructure.repository.DocumentChunkRepository;
import com.aiagent.shared.exception.ResourceStateConflictException;
import com.aiagent.shared.exception.KnowledgeRetrievalUnavailableException;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class DocumentService {

    private final EmbeddingModel embeddingModel;
    private final VectorStoreService vectorStoreService;
    private final DocumentRepository documentRepository;
    private final DocumentIngestionService documentIngestionService;
    private final PlatformMetricsService metricsService;
    private final IdempotencyService idempotencyService;
    private final DocumentStagingStorage stagingStorage;
    private final AiProperties aiProperties;
    private final DocumentParserFactory parserFactory;
    private final DocumentChunkRepository documentChunkRepository;

    public DocumentService(EmbeddingModel embeddingModel,
                           VectorStoreService vectorStoreService,
                           DocumentRepository documentRepository,
                           DocumentIngestionService documentIngestionService,
                           PlatformMetricsService metricsService,
                           DocumentStagingStorage stagingStorage,
                           AiProperties aiProperties,
                           DocumentParserFactory parserFactory,
                           DocumentChunkRepository documentChunkRepository) {
        this(embeddingModel, vectorStoreService, documentRepository, documentIngestionService,
                metricsService, stagingStorage, aiProperties, parserFactory, documentChunkRepository, null);
    }

    @Autowired
    public DocumentService(EmbeddingModel embeddingModel,
                           VectorStoreService vectorStoreService,
                           DocumentRepository documentRepository,
                           DocumentIngestionService documentIngestionService,
                           PlatformMetricsService metricsService,
                           DocumentStagingStorage stagingStorage,
                           AiProperties aiProperties,
                           DocumentParserFactory parserFactory,
                           DocumentChunkRepository documentChunkRepository,
                           IdempotencyService idempotencyService) {
        this.embeddingModel = embeddingModel;
        this.vectorStoreService = vectorStoreService;
        this.documentRepository = documentRepository;
        this.documentIngestionService = documentIngestionService;
        this.metricsService = metricsService;
        this.stagingStorage = stagingStorage;
        this.aiProperties = aiProperties;
        this.parserFactory = parserFactory;
        this.documentChunkRepository = documentChunkRepository;
        this.idempotencyService = idempotencyService;
    }

    public Document uploadDocument(MultipartFile file) {
        String fileName = resolveFileName(file);
        validateSupportedFormat(fileName);
        StagedDocument stagedDocument = stagingStorage.stage(file);
        StagingLease lease = new StagingLease(stagedDocument);
        try {
            return createDocument(fileName, file.getContentType(), lease);
        } finally {
            lease.cleanupIfUnretained(stagingStorage);
        }
    }

    public Document uploadDocument(MultipartFile file, String idempotencyKey) {
        String fileName = resolveFileName(file);
        validateSupportedFormat(fileName);
        StagedDocument stagedDocument = stagingStorage.stage(file);
        StagingLease lease = new StagingLease(stagedDocument);
        String contentHash = stagedDocument.contentHash();
        String contentType = file.getContentType();
        if (!StringUtils.hasText(idempotencyKey)) {
            try {
                return createDocument(fileName, contentType, lease);
            } finally {
                lease.cleanupIfUnretained(stagingStorage);
            }
        }
        if (idempotencyService == null) {
            lease.cleanupIfUnretained(stagingStorage);
            throw new IllegalStateException("Idempotency service is unavailable");
        }
        String requestHash = idempotencyService.fingerprint(
                "document-upload", fileName, contentType, Long.toString(stagedDocument.size()), contentHash);

        try {
            return idempotencyService.execute(
                    "document-upload",
                    idempotencyKey,
                    requestHash,
                    Document.class,
                    () -> createDocument(fileName, contentType, lease));
        } finally {
            lease.cleanupIfUnretained(stagingStorage);
        }
    }

    private String resolveFileName(MultipartFile file) {
        if (file == null) {
            throw new IllegalArgumentException("Uploaded document is required");
        }
        String originalName = file.getOriginalFilename();
        if (!StringUtils.hasText(originalName)) {
            return "unnamed";
        }
        String normalizedName = originalName.replace('\\', '/');
        String fileName = normalizedName.substring(normalizedName.lastIndexOf('/') + 1).trim();
        if (!StringUtils.hasText(fileName) || fileName.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Uploaded document has an invalid file name");
        }
        if (fileName.length() > 255) {
            throw new IllegalArgumentException("Uploaded document file name is too long");
        }
        return fileName;
    }

    private Document createDocument(String fileName, String contentType, StagingLease lease) {
        StagedDocument stagedDocument = lease.document();
        var existingDocument = documentRepository.findByContentHash(stagedDocument.contentHash());
        if (existingDocument.isPresent()) {
            log.info("Document already exists for content hash, reusing document: {}", fileName);
            return existingDocument.get();
        }

        log.info("Queueing document for async processing: {}", fileName);

        Document document = Document.builder()
                .fileName(fileName)
                .fileType(extractFileType(fileName))
                .fileSize(stagedDocument.size())
                .contentHash(stagedDocument.contentHash())
                .contentType(normalizeContentType(contentType))
                .stagingPath(stagedDocument.storedPath())
                .parserVersion(aiProperties.getDocument().getParserVersion())
                .chunkCount(0)
                .build();
        try {
            document = documentRepository.save(document);
        } catch (DataIntegrityViolationException exception) {
            return documentRepository.findByContentHash(stagedDocument.contentHash())
                    .orElseThrow(() -> exception);
        }

        lease.retain();
        try {
            documentIngestionService.ingestAsync(document.getId());
        } catch (RuntimeException exception) {
            log.warn("Document [{}] was persisted but async scheduling was rejected; startup recovery will retry it",
                    document.getId(), exception);
        }
        metricsService.recordDocumentQueued();
        return document;
    }

    private String normalizeContentType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return null;
        }
        return contentType.length() <= 255 ? contentType : contentType.substring(0, 255);
    }

    private void validateSupportedFormat(String fileName) {
        if (!vectorStoreService.isWriteAvailable()) {
            throw new IllegalStateException("Knowledge vector store is unavailable; document upload is temporarily disabled");
        }
        if (!parserFactory.supports(fileName)) {
            throw new IllegalArgumentException("Unsupported document format: " + fileName);
        }
    }

    public Map<String, Object> getDocumentStatus(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("documentId", document.getId());
        response.put("fileName", document.getFileName());
        response.put("fileType", document.getFileType());
        response.put("fileSize", document.getFileSize());
        response.put("contentType", document.getContentType());
        response.put("parserVersion", document.getParserVersion());
        response.put("status", document.getProcessingStatus());
        response.put("chunkCount", document.getChunkCount());
        response.put("errorMessage", document.getErrorMessage());
        response.put("processingStartedAt", document.getProcessingStartedAt());
        response.put("processingCompletedAt", document.getProcessingCompletedAt());
        response.put("createdAt", document.getCreatedAt());
        response.put("updatedAt", document.getUpdatedAt());
        return response;
    }

    public Map<String, Object> getKnowledgeReadiness() {
        boolean available = vectorStoreService.isAvailable();
        boolean writeAvailable = vectorStoreService.isWriteAvailable();
        String status = available ? "READY" : writeAvailable ? "DEGRADED" : "UNAVAILABLE";

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("vectorStoreType", aiProperties.getVectorStore().getType());
        response.put("vectorStoreMode", aiProperties.getVectorStore().getMode());
        response.put("available", available);
        response.put("writeAvailable", writeAvailable);
        response.put("fallbackEnabled", aiProperties.getVectorStore().getMilvus().isFallbackEnabled());
        return response;
    }

    @Transactional
    public Document retryDocument(Long documentId) {
        Document document = getDocumentForUpdate(documentId);
        if (document.getProcessingStatus() != DocumentProcessingStatus.FAILED) {
            throw new ResourceStateConflictException(
                    "Only failed documents can be retried; current status is " + document.getProcessingStatus());
        }
        if (!vectorStoreService.isWriteAvailable()) {
            throw new IllegalStateException("Knowledge vector store is unavailable; retry is temporarily disabled");
        }
        if (!stagingStorage.exists(document.getStagingPath())) {
            throw new ResourceStateConflictException("The staged source file is missing and cannot be retried");
        }

        document.setProcessingStatus(DocumentProcessingStatus.PENDING);
        document.setErrorMessage(null);
        document.setProcessingStartedAt(null);
        document.setProcessingCompletedAt(null);
        document = documentRepository.save(document);
        scheduleAfterCommit(document.getId());
        metricsService.recordDocumentQueued();
        return document;
    }

    @Transactional
    public void deleteDocument(Long documentId) {
        Document document = getDocumentForUpdate(documentId);
        if (document.getProcessingStatus() == DocumentProcessingStatus.PENDING
                || document.getProcessingStatus() == DocumentProcessingStatus.PROCESSING) {
            throw new ResourceStateConflictException(
                    "Pending or processing documents cannot be deleted");
        }

        var chunks = documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId);
        for (var chunk : chunks) {
            if (StringUtils.hasText(chunk.getVectorId())) {
                vectorStoreService.remove(chunk.getVectorId());
            }
        }
        documentRepository.delete(document);
        stagingStorage.deleteQuietly(document.getStagingPath());
    }

    private Document getDocumentForUpdate(Long documentId) {
        return documentRepository.findByIdForUpdate(documentId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found: " + documentId));
    }

    private void scheduleAfterCommit(Long documentId) {
        Runnable scheduler = () -> {
            try {
                documentIngestionService.ingestAsync(documentId);
            } catch (RuntimeException exception) {
                log.warn("Document retry [{}] persisted but scheduling failed; startup recovery will retry it",
                        documentId, exception);
            }
        };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            scheduler.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                scheduler.run();
            }
        });
    }

    public List<RetrievalChunk> searchSimilar(String query, int topK, double threshold) {
        return searchSimilar(query, List.of(topK), threshold).getOrDefault(topK, List.of());
    }

    /**
     * FAQ 优先级联检索（级联第一层）：
     * 先在精选 FAQ 库检索，若 top1 相似度达到 faqHitThreshold 则视为标准问题直接命中并返回 FAQ 结果；
     * 否则返回空列表，由调用方继续回退 raw 库检索。
     */
    public List<RetrievalChunk> searchFaqFirst(String query, int topK,
                                               int faqTopK, double faqHitThreshold) {
        if (!StringUtils.hasText(query) || topK <= 0) {
            return List.of();
        }
        if (!vectorStoreService.isAvailable()) {
            return List.of();
        }
        var queryEmbedding = embeddingModel.embed(query).content();
        if (queryEmbedding == null) {
            return List.of();
        }
        List<dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment>> faqMatches =
                vectorStoreService.searchFaq(queryEmbedding, faqTopK, 0.0);
        if (faqMatches == null || faqMatches.isEmpty()) {
            return List.of();
        }
        if (faqMatches.get(0).score() == null || faqMatches.get(0).score() < faqHitThreshold) {
            return List.of();
        }
        List<RetrievalChunk> chunks = toRetrievalChunks(
                faqMatches.stream().limit(topK).toList());
        return chunks.stream()
                .map(chunk -> {
                    Map<String, Object> meta = new HashMap<>();
                    if (chunk.getMetadata() != null) {
                        meta.putAll(chunk.getMetadata());
                    }
                    meta.put("retrievalSource", "faq");
                    return RetrievalChunk.builder()
                            .id(chunk.getId())
                            .content(chunk.getContent())
                            .score(chunk.getScore())
                            .metadata(meta)
                            .build();
                })
                .toList();
    }

    public Map<Integer, List<RetrievalChunk>> searchSimilar(String query,
                                                            List<Integer> topKs,
                                                            double threshold) {
        List<Integer> sanitizedTopKs = topKs == null
                ? List.of()
                : topKs.stream().filter(topK -> topK != null && topK > 0).distinct().toList();
        if (sanitizedTopKs.isEmpty()) {
            return Map.of();
        }
        if (!vectorStoreService.isAvailable() && !vectorStoreService.isWriteAvailable()) {
            throw new KnowledgeRetrievalUnavailableException(
                    "Knowledge retrieval is temporarily unavailable");
        }
        var queryEmbedding = embeddingModel.embed(query).content();
        Map<Integer, List<RetrievalChunk>> results = new LinkedHashMap<>();
        for (int topK : sanitizedTopKs) {
            List<dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment>> matches =
                    vectorStoreService.search(queryEmbedding, topK, threshold);
            results.put(topK, toRetrievalChunks(matches));
        }
        return results;
    }

    private List<RetrievalChunk> toRetrievalChunks(
            List<dev.langchain4j.store.embedding.EmbeddingMatch<TextSegment>> matches) {
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

    private static final class StagingLease {
        private final StagedDocument document;
        private boolean retained;

        private StagingLease(StagedDocument document) {
            this.document = document;
        }

        private StagedDocument document() {
            return document;
        }

        private void retain() {
            retained = true;
        }

        private void cleanupIfUnretained(DocumentStagingStorage storage) {
            if (!retained) {
                storage.deleteQuietly(document.storedPath());
            }
        }
    }
}
