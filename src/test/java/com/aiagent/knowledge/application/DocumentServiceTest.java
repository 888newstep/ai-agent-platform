package com.aiagent.knowledge.application;

import com.aiagent.knowledge.domain.Document;
import com.aiagent.knowledge.domain.DocumentProcessingStatus;
import com.aiagent.knowledge.domain.DocumentChunk;
import com.aiagent.knowledge.domain.RetrievalChunk;
import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import com.aiagent.knowledge.infrastructure.repository.DocumentRepository;
import com.aiagent.knowledge.infrastructure.repository.DocumentChunkRepository;
import com.aiagent.knowledge.infrastructure.parser.DocumentParserFactory;
import com.aiagent.knowledge.infrastructure.storage.DocumentStagingStorage;
import com.aiagent.knowledge.infrastructure.vectorstore.VectorStoreService;
import com.aiagent.shared.exception.ResourceStateConflictException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private VectorStoreService vectorStoreService;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentIngestionService documentIngestionService;

    @Mock
    private PlatformMetricsService metricsService;

    @Mock
    private DocumentParserFactory parserFactory;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    @TempDir
    Path tempDirectory;

    private DocumentService documentService;
    private DocumentStagingStorage stagingStorage;

    @BeforeEach
    void setUp() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.getDocument().setStagingDirectory(tempDirectory.toString());
        stagingStorage = new DocumentStagingStorage(aiProperties);
        lenient().when(parserFactory.supports(any())).thenReturn(true);
        lenient().when(vectorStoreService.isWriteAvailable()).thenReturn(true);
        documentService = new DocumentService(
                embeddingModel,
                vectorStoreService,
                documentRepository,
                documentIngestionService,
                metricsService,
                stagingStorage,
                aiProperties,
                parserFactory,
                documentChunkRepository
        );
    }

    @Test
    void shouldStageAndQueueDocumentWithoutPassingFileBytes() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "guide.md", "text/markdown", "hello".getBytes());
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            document.setId(123L);
            return document;
        });

        Document document = documentService.uploadDocument(file);

        assertThat(document.getId()).isEqualTo(123L);
        assertThat(document.getProcessingStatus()).isEqualTo(DocumentProcessingStatus.PENDING);
        assertThat(document.getFileSize()).isEqualTo(5L);
        assertThat(document.getContentType()).isEqualTo("text/markdown");
        assertThat(document.getParserVersion()).isEqualTo("v1");
        assertThat(document.getContentHash()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest("hello".getBytes())));
        try (InputStream inputStream = stagingStorage.open(document.getStagingPath())) {
            assertThat(inputStream.readAllBytes()).isEqualTo("hello".getBytes());
        }
        verify(documentIngestionService).ingestAsync(123L);
        verify(metricsService).recordDocumentQueued();
    }

    @Test
    void shouldReuseOneEmbeddingForMultipleVectorTopKs() {
        Embedding embedding = new Embedding(new float[]{0.8f, 0.2f});
        TextSegment segment = TextSegment.from("退款规则");
        when(vectorStoreService.isAvailable()).thenReturn(true);
        when(embeddingModel.embed("怎么退款")).thenReturn(new Response<>(embedding));
        when(vectorStoreService.search(embedding, 5, 0.6)).thenReturn(List.of(
                new EmbeddingMatch<>(0.9, "qa-1", embedding, segment)));
        when(vectorStoreService.search(embedding, 20, 0.6)).thenReturn(List.of(
                new EmbeddingMatch<>(0.9, "qa-1", embedding, segment),
                new EmbeddingMatch<>(0.8, "qa-2", embedding, segment)));

        Map<Integer, List<RetrievalChunk>> results = documentService.searchSimilar(
                "怎么退款", List.of(5, 20), 0.6);

        assertThat(results.get(5)).extracting(RetrievalChunk::getId).containsExactly("qa-1");
        assertThat(results.get(20)).extracting(RetrievalChunk::getId).containsExactly("qa-1", "qa-2");
        verify(embeddingModel, times(1)).embed("怎么退款");
    }

    @Test
    void shouldDeleteNewStagingFileWhenContentAlreadyExists() throws Exception {
        Document existing = Document.builder().id(9L).fileName("existing.md").build();
        when(documentRepository.findByContentHash(any())).thenReturn(Optional.of(existing));

        Document result = documentService.uploadDocument(
                new MockMultipartFile("file", "duplicate.md", "text/markdown", "same".getBytes()));

        assertThat(result).isSameAs(existing);
        try (var files = Files.list(tempDirectory)) {
            assertThat(files).isEmpty();
        }
        verify(documentIngestionService, never()).ingestAsync(any());
    }

    @Test
    void shouldRejectUnsupportedFormatBeforeCreatingStagingFile() throws Exception {
        when(parserFactory.supports("legacy.doc")).thenReturn(false);

        assertThatThrownBy(() -> documentService.uploadDocument(
                new MockMultipartFile("file", "legacy.doc", "application/msword", "data".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported document format");

        try (var files = Files.list(tempDirectory)) {
            assertThat(files).isEmpty();
        }
        verify(documentIngestionService, never()).ingestAsync(any());
    }

    @Test
    void shouldRejectUploadBeforeStagingWhenVectorStoreIsUnavailable() throws Exception {
        when(vectorStoreService.isWriteAvailable()).thenReturn(false);

        assertThatThrownBy(() -> documentService.uploadDocument(
                new MockMultipartFile("file", "guide.md", "text/markdown", "data".getBytes())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("vector store is unavailable");

        try (var files = Files.list(tempDirectory)) {
            assertThat(files).isEmpty();
        }
        verify(documentIngestionService, never()).ingestAsync(any());
    }

    @Test
    void shouldReturnDocumentStatusPayload() {
        Document document = Document.builder()
                .id(7L)
                .fileName("manual.pdf")
                .fileType("pdf")
                .fileSize(42L)
                .chunkCount(3)
                .processingStatus(DocumentProcessingStatus.COMPLETED)
                .build();
        when(documentRepository.findById(7L)).thenReturn(Optional.of(document));

        var status = documentService.getDocumentStatus(7L);

        assertThat(status.get("documentId")).isEqualTo(7L);
        assertThat(status.get("status")).isEqualTo(DocumentProcessingStatus.COMPLETED);
        assertThat(status.get("chunkCount")).isEqualTo(3);
    }

    @Test
    void shouldReportUnavailableKnowledgeReadiness() {
        when(vectorStoreService.isAvailable()).thenReturn(false);
        when(vectorStoreService.isWriteAvailable()).thenReturn(false);

        var readiness = documentService.getKnowledgeReadiness();

        assertThat(readiness)
                .containsEntry("status", "UNAVAILABLE")
                .containsEntry("available", false)
                .containsEntry("writeAvailable", false)
                .containsEntry("fallbackEnabled", false);
    }

    @Test
    void shouldRetryFailedDocumentUsingRetainedStagingFile() {
        var staged = stagingStorage.stage(new MockMultipartFile(
                "file", "failed.md", "text/markdown", "retry body".getBytes()));
        Document document = Document.builder()
                .id(31L)
                .fileName("failed.md")
                .stagingPath(staged.storedPath())
                .processingStatus(DocumentProcessingStatus.FAILED)
                .errorMessage("Milvus unavailable")
                .build();
        when(documentRepository.findByIdForUpdate(31L)).thenReturn(Optional.of(document));
        when(documentRepository.save(document)).thenReturn(document);

        Document retried = documentService.retryDocument(31L);

        assertThat(retried.getProcessingStatus()).isEqualTo(DocumentProcessingStatus.PENDING);
        assertThat(retried.getErrorMessage()).isNull();
        verify(documentIngestionService).ingestAsync(31L);
        verify(metricsService).recordDocumentQueued();
    }

    @Test
    void shouldRejectRetryWhenDocumentIsNotFailed() {
        Document document = Document.builder()
                .id(32L)
                .processingStatus(DocumentProcessingStatus.COMPLETED)
                .build();
        when(documentRepository.findByIdForUpdate(32L)).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> documentService.retryDocument(32L))
                .isInstanceOf(ResourceStateConflictException.class)
                .hasMessageContaining("Only failed documents");
    }

    @Test
    void shouldDeleteVectorsBeforeDeletingCompletedDocument() {
        Document document = Document.builder()
                .id(40L)
                .processingStatus(DocumentProcessingStatus.COMPLETED)
                .build();
        when(documentRepository.findByIdForUpdate(40L)).thenReturn(Optional.of(document));
        when(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(40L)).thenReturn(List.of(
                DocumentChunk.builder().documentId(40L).chunkIndex(0).vectorId("v1").build(),
                DocumentChunk.builder().documentId(40L).chunkIndex(1).vectorId("v2").build()));

        documentService.deleteDocument(40L);

        verify(vectorStoreService).remove("v1");
        verify(vectorStoreService).remove("v2");
        verify(documentRepository).delete(document);
    }

    @Test
    void shouldRejectDeleteWhileDocumentIsProcessing() {
        Document document = Document.builder()
                .id(41L)
                .processingStatus(DocumentProcessingStatus.PROCESSING)
                .build();
        when(documentRepository.findByIdForUpdate(41L)).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> documentService.deleteDocument(41L))
                .isInstanceOf(ResourceStateConflictException.class)
                .hasMessageContaining("cannot be deleted");
    }
}
