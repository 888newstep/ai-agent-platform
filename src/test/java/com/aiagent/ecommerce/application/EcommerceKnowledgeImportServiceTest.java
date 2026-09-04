package com.aiagent.ecommerce.application;

import com.aiagent.ecommerce.config.EcommerceProperties;
import com.aiagent.ecommerce.infrastructure.repository.EcommerceQaPairRepository;
import com.aiagent.infrastructure.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EcommerceKnowledgeImportServiceTest {

    @TempDir
    Path tempDir;

    private EcommerceKnowledgeImportService service;
    private QaClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = mock(QaClassifier.class);
        when(classifier.classify(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn("shipping");
        service = new EcommerceKnowledgeImportService(
                null,
                new ObjectMapper(),
                mock(EcommerceQaPairRepository.class),
                new EcommerceProperties(),
                new AiProperties(),
                mock(EmbeddingModel.class),
                classifier);
    }

    @Test
    void shouldParseNormalizeAndClassifyValidJsonlWhileSkippingInvalidRows() throws Exception {
        Path jsonl = tempDir.resolve("qa.jsonl");
        Files.writeString(jsonl, String.join("\n",
                "{\"messages\":[{\"role\":\"system\",\"content\":\"客服\"},{\"role\":\"user\",\"content\":\"  什么时候发货？  \"},{\"role\":\"assistant\",\"content\":\"  今天发货。  \"}]}",
                "not-json",
                "",
                "{\"messages\":[{\"role\":\"system\",\"content\":\"客服\"},{\"role\":\"user\",\"content\":\"\"},{\"role\":\"assistant\",\"content\":\"无\"}]}"
        ), StandardCharsets.UTF_8);

        List<EcommerceKnowledgeImportService.QaRecord> records = service.parseJsonl(jsonl.toString());

        assertThat(records).hasSize(1);
        EcommerceKnowledgeImportService.QaRecord record = records.get(0);
        assertThat(record.getQuestion()).isEqualTo("什么时候发货?");
        assertThat(record.getAnswer()).isEqualTo("今天发货。");
        assertThat(record.getQaText()).isEqualTo("用户问题：什么时候发货? 客服回答：今天发货。");
        assertThat(record.getCategory()).isEqualTo("shipping");
        assertThat(record.getRecordHash()).isEqualTo(
                EcommerceKnowledgeImportService.recordHash("什么时候发货?", "今天发货。"));
    }

    @Test
    void shouldGenerateStableHashAfterNormalization() {
        String first = EcommerceKnowledgeImportService.recordHash(" 何时发货 ", "今天");
        String second = EcommerceKnowledgeImportService.recordHash("何时发货", " 今天 ");

        assertThat(first).isEqualTo(second).hasSize(64);
    }

    @Test
    void shouldRejectMissingJsonlFile() {
        assertThatThrownBy(() -> service.parseJsonl(tempDir.resolve("missing.jsonl").toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件不存在");
    }
}
