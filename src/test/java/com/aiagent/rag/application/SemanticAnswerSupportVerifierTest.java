package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SemanticAnswerSupportVerifierTest {

    @Mock
    private EmbeddingModel embeddingModel;

    private SemanticAnswerSupportVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new SemanticAnswerSupportVerifier(embeddingModel, new AiProperties());
    }

    @Test
    void shouldAcceptAnswerWhenEveryClaimIsSupported() {
        when(embeddingModel.embedAll(anyList())).thenReturn(response(
                embedding(1.0f, 0.0f),
                embedding(1.0f, 0.0f)
        ));

        AnswerSupportResult result = verifier.verify(
                "如何退款？",
                "可以在订单页申请退款。",
                "[1] 可以在订单页申请退款。"
        );

        assertThat(result.isSupported()).isTrue();
        assertThat(result.getSupportedClaimRatio()).isEqualTo(1.0);
        assertThat(result.isNumbersSupported()).isTrue();
    }

    @Test
    void shouldRejectAnswerThatIntroducesUnsupportedNumber() {
        AnswerSupportResult result = verifier.verify(
                "退款多久到账？",
                "退款会在1天内到账。",
                "[1] 退款审核通过后原路到账，到账时间以支付渠道为准。"
        );

        assertThat(result.isSupported()).isFalse();
        assertThat(result.isNumbersSupported()).isFalse();
    }

    @Test
    void shouldEvaluatePositiveAndNegativeEvidenceClausesSeparately() {
        when(embeddingModel.embedAll(anyList())).thenReturn(response(
                embedding(1.0f, 0.0f),
                embedding(1.0f, 0.0f),
                embedding(1.0f, 0.0f)
        ));

        AnswerSupportResult result = verifier.verify(
                "订单能否修改收货地址？",
                "发货前可以修改收货地址。",
                "[1] 订单发货前可以修改收货地址，发货后不可修改。"
        );

        assertThat(result.isSupported()).isTrue();
        assertThat(result.getSupportedClaimCount()).isEqualTo(1);
    }

    @Test
    void shouldRejectAnswerWhenSupportedClaimRatioIsTooLow() {
        when(embeddingModel.embedAll(anyList())).thenReturn(response(
                embedding(1.0f, 0.0f),
                embedding(0.0f, 1.0f),
                embedding(1.0f, 0.0f)
        ));

        AnswerSupportResult result = verifier.verify(
                "如何退款？",
                "可以申请退款。退款将自动赠送优惠券。",
                "[1] 可以申请退款。"
        );

        assertThat(result.isSupported()).isFalse();
        assertThat(result.getSupportedClaimCount()).isEqualTo(1);
        assertThat(result.getSupportedClaimRatio()).isEqualTo(0.5);
    }

    @Test
    void shouldRejectSemanticallySimilarContradiction() {
        when(embeddingModel.embedAll(anyList())).thenReturn(response(
                embedding(1.0f, 0.0f),
                embedding(1.0f, 0.0f)
        ));

        AnswerSupportResult result = verifier.verify(
                "该商品支持退款吗？",
                "该商品支持退款。",
                "[1] 该商品不支持退款。"
        );

        assertThat(result.isSupported()).isFalse();
        assertThat(result.getSupportedClaimCount()).isZero();
    }

    @Test
    void shouldFailClosedWhenEmbeddingProviderFails() {
        when(embeddingModel.embedAll(anyList())).thenThrow(new IllegalStateException("provider timeout"));

        assertThatThrownBy(() -> verifier.verify(
                "如何退款？", "可以申请退款。", "[1] 可以申请退款。"))
                .isInstanceOf(KnowledgeRetrievalUnavailableException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private Response<List<Embedding>> response(Embedding... embeddings) {
        return new Response<>(List.of(embeddings));
    }

    private Embedding embedding(float... vector) {
        return new Embedding(vector);
    }
}
