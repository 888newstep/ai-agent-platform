package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.domain.RetrievalChunk;
import com.aiagent.shared.exception.KnowledgeRetrievalUnavailableException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticEvidenceRerankerTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private CrossEncoderRerankClient crossEncoderRerankClient;

    private SemanticEvidenceReranker reranker;
    private AiProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.getRag().getAdaptive().setSemanticRerankTopK(2);
        reranker = new SemanticEvidenceReranker(embeddingModel, properties, crossEncoderRerankClient);
    }

    @Test
    void shouldRerankBySemanticScoreAndFilterWeakChunks() {
        List<RetrievalChunk> chunks = List.of(
                chunk("medium", Map.of("source", "vector")),
                chunk("best", Map.of()),
                chunk("weak", Map.of())
        );
        when(embeddingModel.embedAll(anyList())).thenReturn(new Response<>(List.of(
                embedding(1.0f, 0.0f),
                embedding(0.8f, 0.6f),
                embedding(1.0f, 0.0f),
                embedding(0.0f, 1.0f)
        )));

        List<RetrievalChunk> result = reranker.rerank("退款流程", chunks);

        assertThat(result).extracting(RetrievalChunk::getId).containsExactly("best", "medium");
        assertThat(result.get(0).getMetadata())
                .containsEntry(SemanticEvidenceReranker.ORIGINAL_RANK_METADATA, 2);
        assertThat(result.get(1).getMetadata())
                .containsEntry("source", "vector")
                .containsEntry(SemanticEvidenceReranker.ORIGINAL_RANK_METADATA, 1);
        assertThat((Double) result.get(0).getMetadata()
                .get(SemanticEvidenceReranker.SEMANTIC_SCORE_METADATA)).isEqualTo(1.0);
    }

    @Test
    void shouldFailClosedWhenEmbeddingResponseIsInvalid() {
        when(embeddingModel.embedAll(anyList())).thenReturn(new Response<>(List.of(embedding(1.0f, 0.0f))));

        assertThatThrownBy(() -> reranker.rerank("退款流程", List.of(chunk("doc-1", Map.of()))))
                .isInstanceOf(KnowledgeRetrievalUnavailableException.class)
                .hasMessageContaining("invalid embedding response");
    }

    @Test
    void shouldFailClosedWhenEmbeddingProviderFails() {
        when(embeddingModel.embedAll(anyList())).thenThrow(new IllegalStateException("provider timeout"));

        assertThatThrownBy(() -> reranker.rerank("退款流程", List.of(chunk("doc-1", Map.of()))))
                .isInstanceOf(KnowledgeRetrievalUnavailableException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldUseCrossEncoderScoresWhenConfigured() {
        properties.getRag().getAdaptive().setRerankProvider("cross-encoder");
        when(crossEncoderRerankClient.rerank(anyString(), anyList())).thenReturn(List.of(
                new CrossEncoderRerankClient.RerankScore(0, 0.71),
                new CrossEncoderRerankClient.RerankScore(1, 0.96)
        ));

        List<RetrievalChunk> result = reranker.rerank("退款流程", List.of(
                chunk("less-relevant", Map.of()),
                chunk("best", Map.of())
        ));

        assertThat(result).extracting(RetrievalChunk::getId).containsExactly("best", "less-relevant");
        assertThat(result.get(0).getMetadata())
                .containsEntry(SemanticEvidenceReranker.RERANK_PROVIDER_METADATA, "cross-encoder");
    }

    @Test
    void shouldFailClosedWhenCrossEncoderResponseOmitsScore() {
        properties.getRag().getAdaptive().setRerankProvider("cross-encoder");
        when(crossEncoderRerankClient.rerank(anyString(), anyList())).thenReturn(List.of(
                new CrossEncoderRerankClient.RerankScore(0, 0.91)
        ));

        assertThatThrownBy(() -> reranker.rerank("退款流程", List.of(
                chunk("doc-1", Map.of()),
                chunk("doc-2", Map.of())
        )))
                .isInstanceOf(KnowledgeRetrievalUnavailableException.class)
                .hasMessageContaining("every evidence chunk");
    }

    @Test
    void shouldOnlyFallbackWhenExplicitlyEnabled() {
        properties.getRag().getAdaptive().setRerankProvider("cross-encoder");
        properties.getRag().getAdaptive().getCrossEncoder().setFallbackToEmbedding(true);
        when(crossEncoderRerankClient.rerank(anyString(), anyList()))
                .thenThrow(new KnowledgeRetrievalUnavailableException("reranker down"));
        when(embeddingModel.embedAll(anyList())).thenReturn(new Response<>(List.of(
                embedding(1.0f, 0.0f),
                embedding(1.0f, 0.0f)
        )));

        List<RetrievalChunk> result = reranker.rerank(
                "退款流程", List.of(chunk("doc-1", Map.of())));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getMetadata())
                .containsEntry(SemanticEvidenceReranker.RERANK_PROVIDER_METADATA, "embedding-fallback");
    }

    @Test
    void shouldFailClosedForUnsupportedProvider() {
        properties.getRag().getAdaptive().setRerankProvider("unknown");

        assertThatThrownBy(() -> reranker.rerank(
                "退款流程", List.of(chunk("doc-1", Map.of()))))
                .isInstanceOf(KnowledgeRetrievalUnavailableException.class)
                .hasMessageContaining("Unsupported RAG rerank provider");
    }

    @Test
    void shouldFailClosedForInvalidCrossEncoderThreshold() {
        properties.getRag().getAdaptive().setRerankProvider("cross-encoder");
        properties.getRag().getAdaptive().getCrossEncoder().setMinScore(1.5);

        assertThatThrownBy(() -> reranker.rerank(
                "退款流程", List.of(chunk("doc-1", Map.of()))))
                .isInstanceOf(KnowledgeRetrievalUnavailableException.class)
                .hasMessageContaining("must be between 0 and 1");
    }

    private RetrievalChunk chunk(String id, Map<String, Object> metadata) {
        return RetrievalChunk.builder()
                .id(id)
                .content(id + " evidence")
                .metadata(metadata)
                .build();
    }

    private Embedding embedding(float... vector) {
        return new Embedding(vector);
    }
}
