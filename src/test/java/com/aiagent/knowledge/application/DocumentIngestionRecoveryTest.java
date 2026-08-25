package com.aiagent.knowledge.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.domain.Document;
import com.aiagent.knowledge.domain.DocumentProcessingStatus;
import com.aiagent.knowledge.infrastructure.repository.DocumentRepository;
import com.aiagent.knowledge.infrastructure.storage.DocumentStagingStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionRecoveryTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentIngestionService ingestionService;

    @TempDir
    Path tempDirectory;

    private DocumentStagingStorage stagingStorage;
    private DocumentIngestionRecovery recovery;

    @BeforeEach
    void setUp() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.getDocument().setStagingDirectory(tempDirectory.toString());
        stagingStorage = new DocumentStagingStorage(aiProperties);
        recovery = new DocumentIngestionRecovery(documentRepository, ingestionService, stagingStorage);
    }

    @Test
    void shouldRecoverPendingAndProcessingDocuments() {
        Document pending = Document.builder().id(1L).stagingPath("one.staged").build();
        Document processing = Document.builder()
                .id(2L)
                .stagingPath("two.staged")
                .processingStatus(DocumentProcessingStatus.PROCESSING)
                .build();
        when(documentRepository.findByProcessingStatusAndStagingPathIsNotNull(DocumentProcessingStatus.COMPLETED))
                .thenReturn(List.of());
        when(documentRepository.findByProcessingStatusInAndStagingPathIsNotNull(List.of(
                DocumentProcessingStatus.PENDING, DocumentProcessingStatus.PROCESSING)))
                .thenReturn(List.of(pending, processing));

        recovery.recoverInterruptedIngestion();

        verify(ingestionService).ingestAsync(1L);
        verify(ingestionService).ingestAsync(2L);
    }

    @Test
    void shouldCleanLeftoverStagingForCompletedDocument() throws Exception {
        var staged = stagingStorage.stage(new MockMultipartFile(
                "file", "done.txt", "text/plain", "done".getBytes()));
        Document completed = Document.builder()
                .id(3L)
                .stagingPath(staged.storedPath())
                .processingStatus(DocumentProcessingStatus.COMPLETED)
                .build();
        when(documentRepository.findByProcessingStatusAndStagingPathIsNotNull(DocumentProcessingStatus.COMPLETED))
                .thenReturn(List.of(completed));
        when(documentRepository.findByProcessingStatusInAndStagingPathIsNotNull(List.of(
                DocumentProcessingStatus.PENDING, DocumentProcessingStatus.PROCESSING)))
                .thenReturn(List.of());

        recovery.recoverInterruptedIngestion();

        assertThat(tempDirectory.resolve(staged.storedPath())).doesNotExist();
        assertThat(completed.getStagingPath()).isNull();
        verify(documentRepository).save(completed);
    }
}
