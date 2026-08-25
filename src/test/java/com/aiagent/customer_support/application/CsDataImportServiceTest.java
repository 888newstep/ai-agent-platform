package com.aiagent.customer_support.application;

import com.aiagent.customer_support.config.CsProperties;
import com.aiagent.ecommerce.domain.EcommerceQaPair;
import com.aiagent.ecommerce.infrastructure.repository.EcommerceQaPairRepository;
import com.aiagent.infrastructure.config.AiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.utility.request.FlushReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.InsertResp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CsDataImportServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void keepsCheckpointBeforeFailedBatch() throws Exception {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        EcommerceQaPairRepository repository = mock(EcommerceQaPairRepository.class);
        CsProperties properties = new CsProperties();
        properties.setBatchSize(1);

        CsDataImportService service = new CsDataImportService(
                milvusClient,
                embeddingModel,
                new ObjectMapper(),
                repository,
                properties,
                new AiProperties());

        Path dataset = tempDir.resolve("training.jsonl");
        Files.writeString(dataset, """
                {"messages":[{"role":"system","content":"客服"},{"role":"user","content":"如何退款"},{"role":"assistant","content":"提交退款申请"}]}
                """);

        when(embeddingModel.embedAll(any())).thenReturn(
                new Response<>(List.of(new Embedding(new float[]{0.1f, 0.2f}))));
        when(repository.findAllByRecordHashIn(any())).thenReturn(List.of());
        when(repository.save(any(EcommerceQaPair.class))).thenAnswer(invocation -> {
            EcommerceQaPair pair = invocation.getArgument(0);
            pair.setId(1L);
            return pair;
        });
        when(milvusClient.insert(any(InsertReq.class)))
                .thenThrow(new RuntimeException("Milvus unavailable"));

        assertThatThrownBy(() -> service.importFromJsonl(dataset.toString()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("导入失败");

        assertThat(service.getProgress()).isZero();
        assertThat(service.isRunning()).isFalse();
        assertThat(Files.exists(tempDir.resolve("training.jsonl.checkpoint"))).isFalse();
        verify(milvusClient, never()).flush(any(FlushReq.class));
    }

    @Test
    void compensatesMilvusVectorsWhenMysqlVectorIdUpdateFails() throws Exception {
        MilvusClientV2 milvusClient = mock(MilvusClientV2.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        CsImportBatchPersistenceService persistenceService = mock(CsImportBatchPersistenceService.class);
        CsProperties properties = new CsProperties();
        properties.setBatchSize(1);

        CsDataImportService service = new CsDataImportService(
                milvusClient,
                embeddingModel,
                new ObjectMapper(),
                persistenceService,
                properties,
                new AiProperties());

        Path dataset = tempDir.resolve("compensation.jsonl");
        Files.writeString(dataset, """
                {"messages":[{"role":"system","content":"customer service"},{"role":"user","content":"refund"},{"role":"assistant","content":"submit request"}]}
                """);

        EcommerceQaPair pair = EcommerceQaPair.builder().id(11L).recordHash("hash").build();
        when(embeddingModel.embedAll(any())).thenReturn(
                new Response<>(List.of(new Embedding(new float[]{0.1f, 0.2f}))));
        when(persistenceService.prepareBatch(any())).thenReturn(
                new CsImportBatchPersistenceService.PreparedBatch(List.of(
                        new CsImportBatchPersistenceService.PendingPair(0, pair))));
        InsertResp insertResponse = mock(InsertResp.class);
        when(insertResponse.getPrimaryKeys()).thenReturn(List.of(101L));
        when(milvusClient.insert(any(InsertReq.class))).thenReturn(insertResponse);
        org.mockito.Mockito.doThrow(new RuntimeException("MySQL unavailable"))
                .when(persistenceService).updateVectorIds(any());

        assertThatThrownBy(() -> service.importFromJsonl(dataset.toString()))
                .isInstanceOf(RuntimeException.class);

        verify(milvusClient, times(2)).delete(any(DeleteReq.class));
        verify(milvusClient, never()).flush(any(FlushReq.class));
    }
}
