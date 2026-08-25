package com.aiagent.knowledge.infrastructure.vectorstore;

import com.aiagent.infrastructure.config.AiProperties;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusQaVectorStoreServiceTest {

    @Test
    void shouldUseRequiredFilterWhenFetchingCorpus() {
        AiProperties properties = new AiProperties();
        properties.getVectorStore().getMilvus().setCollectionName("ecommerce_qa");
        MilvusClientV2 client = mock(MilvusClientV2.class);
        when(client.hasCollection(any(HasCollectionReq.class))).thenReturn(true);
        when(client.query(any(QueryReq.class))).thenReturn(QueryResp.builder()
                .queryResults(List.of(QueryResp.QueryResult.builder()
                        .entity(Map.of(
                                "qa_pair_id", 42L,
                                "qa_text", "Password reset instructions"))
                        .build()))
                .build());
        MilvusQaVectorStoreService store = new MilvusQaVectorStoreService(properties, client);
        store.init();

        var chunks = store.fetchAllChunks(10);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getId()).isEqualTo("42");
        ArgumentCaptor<QueryReq> requestCaptor = ArgumentCaptor.forClass(QueryReq.class);
        verify(client).query(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getFilter()).isEqualTo("qa_pair_id >= 0");
        assertThat(requestCaptor.getValue().getLimit()).isEqualTo(10);
    }
}
