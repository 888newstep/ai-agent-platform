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
import dev.langchain4j.model.output.Response;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentIngestionServiceTest {

    @Mock
    private DocumentParserFactory parserFactory;

    @Mock
    private TextSplitter textSplitter;

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private VectorStoreService vectorStoreService;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentChunkRepository documentChunkRepository;

    @Mock
    private PlatformMetricsService metricsService;

    @TempDir
    Path tempDirectory;

    private AiProperties aiProperties;
    private Timer.Sample sample;
    private DocumentIngestionService service;
    private DocumentStagingStorage stagingStorage;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.getDocument().setChunkSize(100);
        aiProperties.getDocument().setChunkOverlap(20);
        aiProperties.getDocument().setStagingDirectory(tempDirectory.toString());
        stagingStorage = new DocumentStagingStorage(aiProperties);
        sample = Timer.start();

        service = new DocumentIngestionService(
                parserFactory,
                textSplitter,
                embeddingModel,
                vectorStoreService,
                aiProperties,
                documentRepository,
                documentChunkRepository,
                metricsService,
                stagingStorage
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Test
    void shouldProcessContentAndPersistChunks() {
        List<TextSegment> segments = List.of(TextSegment.from("first chunk"), TextSegment.from("second chunk"));
        when(textSplitter.split("body", 100, 20)).thenReturn(segments);
        when(embeddingModel.embedAll(segments)).thenReturn(new Response<>(List.of(
                new Embedding(new float[]{1f}),
                new Embedding(new float[]{2f})
        )));
        when(vectorStoreService.addAll(any(), eq(segments))).thenReturn(List.of("v1", "v2"));

        int count = service.processContent("guide.md", "body", 9L);

        assertThat(count).isEqualTo(2);
        assertThat(segments.get(0).metadata().getString("fileName")).isEqualTo("guide.md");
        assertThat(segments.get(0).metadata().getInteger("chunkIndex")).isEqualTo(0);
        assertThat(segments.get(1).metadata().getInteger("totalChunks")).isEqualTo(2);

        ArgumentCaptor<List<com.aiagent.knowledge.domain.DocumentChunk>> captor = ArgumentCaptor.forClass(List.class);
        verify(documentChunkRepository).saveAll(captor.capture());
        List<com.aiagent.knowledge.domain.DocumentChunk> saved = captor.getValue();
        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getDocumentId()).isEqualTo(9L);
        assertThat(saved.get(0).getVectorId()).isEqualTo("v1");
        assertThat(saved.get(1).getVectorId()).isEqualTo("v2");
        assertThat(saved.get(1).getCharCount()).isEqualTo("second chunk".length());
    }

    @Test
    void shouldIngestSuccessfullyUpdateStatusAndDeleteStagingFile() throws Exception {
        var staged = stagingStorage.stage(new MockMultipartFile(
                "file", "guide.md", "text/markdown", "hello".getBytes()));
        Document document = Document.builder()
                .id(7L)
                .fileName("guide.md")
                .stagingPath(staged.storedPath())
                .processingStatus(DocumentProcessingStatus.PENDING)
                .build();
        List<TextSegment> segments = List.of(TextSegment.from("first"));
        when(metricsService.startSample()).thenReturn(sample);
        when(documentRepository.findById(7L)).thenReturn(Optional.of(document));
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(7L)).thenReturn(List.of());
        when(parserFactory.parse(eq("guide.md"), any())).thenReturn("body");
        when(textSplitter.split("body", 100, 20)).thenReturn(segments);
        when(embeddingModel.embedAll(segments)).thenReturn(new Response<>(List.of(new Embedding(new float[]{1f}))));
        when(vectorStoreService.addAll(any(), eq(segments))).thenReturn(List.of("vec-1"));

        service.ingestAsync(7L);

        assertThat(document.getProcessingStatus()).isEqualTo(DocumentProcessingStatus.COMPLETED);
        assertThat(document.getChunkCount()).isEqualTo(1);
        assertThat(document.getProcessingStartedAt()).isNotNull();
        assertThat(document.getProcessingCompletedAt()).isNotNull();
        assertThat(document.getStagingPath()).isNull();
        assertThat(tempDirectory.resolve(staged.storedPath())).doesNotExist();
        verify(metricsService).recordDocumentIngestion("completed", 1, sample);
    }

    @Test
    void shouldMarkFailedAndRetainStagingFileWhenIngestionThrows() throws Exception {
        var staged = stagingStorage.stage(new MockMultipartFile(
                "file", "broken.pdf", "application/pdf", new byte[]{1, 2, 3}));
        Document document = Document.builder()
                .id(8L)
                .fileName("broken.pdf")
                .stagingPath(staged.storedPath())
                .processingStatus(DocumentProcessingStatus.PENDING)
                .build();
        String longError = "x".repeat(1205);
        when(metricsService.startSample()).thenReturn(sample);
        when(documentRepository.findById(8L)).thenReturn(Optional.of(document));
        when(documentRepository.save(any(Document.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(8L)).thenReturn(List.of());
        when(parserFactory.parse(eq("broken.pdf"), any())).thenThrow(new RuntimeException(longError));

        service.ingestAsync(8L);

        assertThat(document.getProcessingStatus()).isEqualTo(DocumentProcessingStatus.FAILED);
        assertThat(document.getErrorMessage()).hasSize(1000);
        assertThat(document.getProcessingCompletedAt()).isNotNull();
        assertThat(document.getStagingPath()).isEqualTo(staged.storedPath());
        assertThat(tempDirectory.resolve(staged.storedPath())).exists();
        verify(metricsService).recordDocumentIngestion("failed", 0, sample);
    }
}
