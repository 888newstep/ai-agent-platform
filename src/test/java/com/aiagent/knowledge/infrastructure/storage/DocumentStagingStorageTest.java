package com.aiagent.knowledge.infrastructure.storage;

import com.aiagent.infrastructure.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentStagingStorageTest {

    @TempDir
    Path tempDirectory;

    private DocumentStagingStorage storage;

    @BeforeEach
    void setUp() {
        AiProperties aiProperties = new AiProperties();
        aiProperties.getDocument().setStagingDirectory(tempDirectory.toString());
        storage = new DocumentStagingStorage(aiProperties);
    }

    @Test
    void shouldStageFileAndReportActualSizeAndHash() throws Exception {
        var staged = storage.stage(new MockMultipartFile(
                "file", "guide.txt", "text/plain", "knowledge".getBytes()));

        assertThat(staged.size()).isEqualTo(9L);
        assertThat(staged.contentHash()).hasSize(64);
        try (var inputStream = storage.open(staged.storedPath())) {
            assertThat(inputStream.readAllBytes()).isEqualTo("knowledge".getBytes());
        }
    }

    @Test
    void shouldRejectPathTraversal() {
        assertThatThrownBy(() -> storage.open("../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes");
    }
}
