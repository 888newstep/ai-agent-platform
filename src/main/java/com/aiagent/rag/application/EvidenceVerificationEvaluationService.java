package com.aiagent.rag.application;

import com.aiagent.knowledge.domain.RetrievalChunk;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class EvidenceVerificationEvaluationService {

    private final ObjectMapper objectMapper;
    private final SelfRagVerifier selfRagVerifier;
    private final EvidenceReranker evidenceReranker;

    @Value("${ai.evaluation.dataset-directory:}")
    private String datasetDirectory;

    public EvaluationReport evaluateFromFile(String datasetPath, boolean liveRerank) {
        Path path = resolveDatasetPath(datasetPath);
        List<EvaluationCase> cases = loadCases(path);
        List<CaseResult> results = new ArrayList<>(cases.size());
        ConfusionAccumulator overall = new ConfusionAccumulator();
        Map<String, ConfusionAccumulator> categories = new LinkedHashMap<>();

        for (EvaluationCase evaluationCase : cases) {
            List<RetrievalChunk> chunks = prepareChunks(evaluationCase, liveRerank);
            AdaptiveRagRewriteResult rewrite = AdaptiveRagRewriteResult.builder()
                    .originalQuery(evaluationCase.getQuestion())
                    .rewrittenQuery(evaluationCase.getQuestion())
                    .keywords(evaluationCase.getKeywords())
                    .build();
            AdaptiveRagVerificationResult verification = selfRagVerifier.verify(
                    evaluationCase.getQuestion(), rewrite, chunks, RagRouteType.SINGLE_HOP);
            boolean predictedSupported = verification.getLevel() == RagVerificationLevel.HIGH;
            overall.add(evaluationCase.getExpectedSupported(), predictedSupported);
            categories.computeIfAbsent(evaluationCase.getCategory(), ignored -> new ConfusionAccumulator())
                    .add(evaluationCase.getExpectedSupported(), predictedSupported);
            results.add(CaseResult.builder()
                    .question(evaluationCase.getQuestion())
                    .category(evaluationCase.getCategory())
                    .expectedSupported(evaluationCase.getExpectedSupported())
                    .predictedSupported(predictedSupported)
                    .verificationLevel(verification.getLevel())
                    .score(verification.getScore())
                    .reason(verification.getReason())
                    .note(evaluationCase.getNote())
                    .build());
        }

        Map<String, VerificationMetrics> categoryMetrics = new LinkedHashMap<>();
        categories.forEach((category, accumulator) -> categoryMetrics.put(category, accumulator.metrics()));
        return EvaluationReport.builder()
                .datasetSource(buildDatasetSource(path))
                .liveRerank(liveRerank)
                .sampleCount(cases.size())
                .metrics(overall.metrics())
                .categoryMetrics(categoryMetrics)
                .cases(results)
                .build();
    }

    private List<RetrievalChunk> prepareChunks(EvaluationCase evaluationCase, boolean liveRerank) {
        RetrievalChunk chunk = RetrievalChunk.builder()
                .id(evaluationCase.getCategory() + "-evidence")
                .content(evaluationCase.getEvidence())
                .metadata(Map.of())
                .build();
        if (liveRerank) {
            return evidenceReranker.rerank(evaluationCase.getQuestion(), List.of(chunk));
        }
        if (evaluationCase.getFixtureSemanticScore() == null
                || !Double.isFinite(evaluationCase.getFixtureSemanticScore())
                || evaluationCase.getFixtureSemanticScore() < 0.0
                || evaluationCase.getFixtureSemanticScore() > 1.0) {
            throw new IllegalArgumentException(
                    "fixtureSemanticScore must be between 0 and 1 when liveRerank=false");
        }
        return List.of(RetrievalChunk.builder()
                .id(chunk.getId())
                .content(chunk.getContent())
                .metadata(Map.of(
                        SemanticEvidenceReranker.SEMANTIC_SCORE_METADATA,
                        evaluationCase.getFixtureSemanticScore()))
                .build());
    }

    private List<EvaluationCase> loadCases(Path path) {
        try {
            List<EvaluationCase> cases = objectMapper.readValue(path.toFile(), new TypeReference<>() {
            });
            if (cases == null || cases.isEmpty()) {
                throw new IllegalArgumentException("Evidence evaluation dataset must not be empty");
            }
            for (EvaluationCase evaluationCase : cases) {
                validateCase(evaluationCase);
            }
            return cases;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to read evidence evaluation dataset", exception);
        }
    }

    private void validateCase(EvaluationCase evaluationCase) {
        if (evaluationCase == null
                || !StringUtils.hasText(evaluationCase.getQuestion())
                || !StringUtils.hasText(evaluationCase.getEvidence())
                || evaluationCase.getKeywords() == null
                || evaluationCase.getKeywords().isEmpty()
                || evaluationCase.getExpectedSupported() == null) {
            throw new IllegalArgumentException(
                    "Every evidence evaluation case requires question, evidence, keywords and expectedSupported");
        }
        if (!StringUtils.hasText(evaluationCase.getCategory())) {
            evaluationCase.setCategory("uncategorized");
        }
    }

    private Path resolveDatasetPath(String datasetPath) {
        if (!StringUtils.hasText(datasetPath)) {
            throw new IllegalArgumentException("datasetPath must not be blank");
        }
        try {
            Path path = Path.of(datasetPath.trim()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || !path.getFileName().toString().toLowerCase().endsWith(".json")) {
                throw new IllegalArgumentException("Evidence evaluation dataset must be an existing JSON file");
            }
            Path realPath = path.toRealPath();
            if (StringUtils.hasText(datasetDirectory)) {
                Path allowedDirectory = Path.of(datasetDirectory).toAbsolutePath().normalize().toRealPath();
                if (!realPath.startsWith(allowedDirectory)) {
                    throw new IllegalArgumentException("Dataset path is outside the configured directory");
                }
            }
            return realPath;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Failed to resolve evidence evaluation dataset", exception);
        }
    }

    private String buildDatasetSource(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return path.getFileName() + "#" + HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Failed to fingerprint evidence evaluation dataset", exception);
        }
    }

    private static final class ConfusionAccumulator {
        private int truePositive;
        private int trueNegative;
        private int falsePositive;
        private int falseNegative;

        private void add(boolean expected, boolean predicted) {
            if (expected && predicted) {
                truePositive++;
            } else if (!expected && !predicted) {
                trueNegative++;
            } else if (predicted) {
                falsePositive++;
            } else {
                falseNegative++;
            }
        }

        private VerificationMetrics metrics() {
            int count = truePositive + trueNegative + falsePositive + falseNegative;
            double precision = ratio(truePositive, truePositive + falsePositive);
            double recall = ratio(truePositive, truePositive + falseNegative);
            return VerificationMetrics.builder()
                    .sampleCount(count)
                    .truePositive(truePositive)
                    .trueNegative(trueNegative)
                    .falsePositive(falsePositive)
                    .falseNegative(falseNegative)
                    .accuracy(ratio(truePositive + trueNegative, count))
                    .precision(precision)
                    .recall(recall)
                    .f1(precision + recall == 0.0 ? 0.0 : 2 * precision * recall / (precision + recall))
                    .falsePositiveRate(ratio(falsePositive, falsePositive + trueNegative))
                    .build();
        }

        private double ratio(int numerator, int denominator) {
            return denominator == 0 ? 0.0 : (double) numerator / denominator;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvaluationCase {
        private String question;
        private List<String> keywords;
        private String evidence;
        private Double fixtureSemanticScore;
        private Boolean expectedSupported;
        private String category;
        private String note;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EvaluationReport {
        private String datasetSource;
        private boolean liveRerank;
        private int sampleCount;
        private VerificationMetrics metrics;
        private Map<String, VerificationMetrics> categoryMetrics;
        private List<CaseResult> cases;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerificationMetrics {
        private int sampleCount;
        private int truePositive;
        private int trueNegative;
        private int falsePositive;
        private int falseNegative;
        private double accuracy;
        private double precision;
        private double recall;
        private double f1;
        private double falsePositiveRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CaseResult {
        private String question;
        private String category;
        private boolean expectedSupported;
        private boolean predictedSupported;
        private RagVerificationLevel verificationLevel;
        private double score;
        private String reason;
        private String note;
    }
}
