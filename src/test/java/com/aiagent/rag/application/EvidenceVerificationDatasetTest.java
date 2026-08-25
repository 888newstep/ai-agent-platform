package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.knowledge.domain.RetrievalChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceVerificationDatasetTest {

    @Test
    void shouldMatchHumanLabeledEvidenceDecisions() throws Exception {
        List<EvaluationCase> cases = new ObjectMapper().readValue(
                Path.of("examples/evaluation-datasets/evidence-verification-sample.json").toFile(),
                new TypeReference<>() {
                });
        SelfRagVerifier verifier = new SelfRagVerifier(new AiProperties());

        for (EvaluationCase evaluationCase : cases) {
            AdaptiveRagRewriteResult rewrite = AdaptiveRagRewriteResult.builder()
                    .originalQuery(evaluationCase.question())
                    .rewrittenQuery(evaluationCase.question())
                    .keywords(evaluationCase.keywords())
                    .build();
            RetrievalChunk chunk = RetrievalChunk.builder()
                    .id(evaluationCase.category())
                    .content(evaluationCase.evidence())
                    .metadata(Map.of(
                            SemanticEvidenceReranker.SEMANTIC_SCORE_METADATA,
                            evaluationCase.fixtureSemanticScore()))
                    .build();

            AdaptiveRagVerificationResult result = verifier.verify(
                    evaluationCase.question(), rewrite, List.of(chunk), RagRouteType.SINGLE_HOP);

            assertThat(result.getLevel() == RagVerificationLevel.HIGH)
                    .as("%s: %s; verifier=%s", evaluationCase.category(), evaluationCase.note(), result.getReason())
                    .isEqualTo(evaluationCase.expectedSupported());
        }
    }

    private record EvaluationCase(String question,
                                  List<String> keywords,
                                  String evidence,
                                  double fixtureSemanticScore,
                                  boolean expectedSupported,
                                  String category,
                                  String note) {
    }
}
