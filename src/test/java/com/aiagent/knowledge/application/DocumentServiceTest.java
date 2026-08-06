package com.aiagent.knowledge.application;

import com.aiagent.knowledge.domain.Document;
import com.aiagent.knowledge.domain.DocumentProcessingStatus;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import com.aiagent.knowledge.infrastructure.repository.DocumentRepository;
import com.aiagent.knowledge.infrastructure.vectorstore.VectorStoreService;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(
                embeddingModel,
                vectorStoreService,
                documentRepository,
                documentIngestionService,
                metricsService
        );
    }

    @Test
    void shouldQueueDocumentForAsyncProcessing() {
        MockMultipartFile file = new MockMultipartFile("file", "guide.md", "text/markdown", "hello".getBytes());
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> {
            Document document = invocation.getArgument(0);
            document.setId(123L);
            return document;
        });

        Document document = documentService.uploadDocument(file);

        assertThat(document.getId()).isEqualTo(123L);
        assertThat(document.getProcessingStatus()).isEqualTo(DocumentProcessingStatus.PENDING);
        verify(documentIngestionService).ingestAsync(123L, "guide.md", "hello".getBytes());
        verify(metricsService).recordDocumentQueued();
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
}
