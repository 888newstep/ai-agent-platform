package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.domain.RetrievalChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SelfRagVerifierTest {

    private SelfRagVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new SelfRagVerifier(new AiProperties());
    }

    @Test
    void shouldRejectHardNegativeThatOnlySharesOneKeyword() {
        AdaptiveRagVerificationResult result = verify(
                "退款多久到账？",
                List.of("退款", "到账"),
                chunk("订单支持申请退款。", 0.95));

        assertThat(result.getLevel()).isEqualTo(RagVerificationLevel.NONE);
        assertThat(result.isEvidenceSufficient()).isFalse();
        assertThat(result.getKeywordCoverage()).isEqualTo(0.0);
    }

    @Test
    void shouldRejectChunkWithoutSemanticRerankScore() {
        RetrievalChunk chunk = RetrievalChunk.builder()
                .id("doc-1")
                .content("退款审核后会原路到账")
                .score(0.99)
                .metadata(Map.of())
                .build();

        AdaptiveRagVerificationResult result = verify(
                "退款多久到账？", List.of("退款", "到账"), chunk);

        assertThat(result.getLevel()).isEqualTo(RagVerificationLevel.NONE);
        assertThat(result.getSemanticScore()).isZero();
    }

    @Test
    void shouldAcceptEvidenceWithStrongSemanticAndKeywordSupport() {
        AdaptiveRagVerificationResult result = verify(
                "退款多久到账？",
                List.of("退款", "到账"),
                chunk("退款审核通过后原路返回，到账时间以支付渠道为准。", 0.92));

        assertThat(result.getLevel()).isEqualTo(RagVerificationLevel.HIGH);
        assertThat(result.isEvidenceSufficient()).isTrue();
        assertThat(result.getKeywordCoverage()).isEqualTo(1.0);
    }

    @Test
    void shouldRejectEvidenceMissingNumberFromQuestion() {
        AdaptiveRagVerificationResult result = verify(
                "退款是否会在7天内到账？",
                List.of("退款", "到账"),
                chunk("退款审核通过后会原路到账，具体时间以支付渠道为准。", 0.94));

        assertThat(result.getLevel()).isEqualTo(RagVerificationLevel.NONE);
        assertThat(result.getReason()).contains("numbersSupported=false");
    }

    @Test
    void shouldUseProviderSpecificRerankThresholdFromMetadata() {
        RetrievalChunk chunk = RetrievalChunk.builder()
                .id("doc-1")
                .content("退款审核通过后原路到账")
                .metadata(Map.of(
                        SemanticEvidenceReranker.SEMANTIC_SCORE_METADATA, 0.58,
                        SemanticEvidenceReranker.RERANK_MIN_SCORE_METADATA, 0.55,
                        SemanticEvidenceReranker.RERANK_PROVIDER_METADATA, "cross-encoder"))
                .build();

        AdaptiveRagVerificationResult result = verify(
                "退款多久到账？", List.of("退款", "到账"), chunk);

        assertThat(result.getLevel()).isEqualTo(RagVerificationLevel.HIGH);
        assertThat(result.isEvidenceSufficient()).isTrue();
    }

    private AdaptiveRagVerificationResult verify(String question,
                                                  List<String> keywords,
                                                  RetrievalChunk chunk) {
        AdaptiveRagRewriteResult rewrite = AdaptiveRagRewriteResult.builder()
                .originalQuery(question)
                .rewrittenQuery(question)
                .keywords(keywords)
                .build();
        return verifier.verify(question, rewrite, List.of(chunk), RagRouteType.SINGLE_HOP);
    }

    private RetrievalChunk chunk(String content, double semanticScore) {
        return RetrievalChunk.builder()
                .id("doc-1")
                .content(content)
                .metadata(Map.of(SemanticEvidenceReranker.SEMANTIC_SCORE_METADATA, semanticScore))
                .build();
    }
}
