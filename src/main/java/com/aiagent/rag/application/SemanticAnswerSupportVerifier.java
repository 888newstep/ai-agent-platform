package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.shared.exception.KnowledgeRetrievalUnavailableException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.CosineSimilarity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class SemanticAnswerSupportVerifier implements AnswerSupportVerifier {

    private static final Pattern CLAIM_SEPARATOR = Pattern.compile("(?<=[。！？.!?])\\s*|[\\r\\n]+");
    private static final Pattern EVIDENCE_SEPARATOR = Pattern.compile("(?<=[。！？.!?；;])\\s*|[，,\\r\\n]+");
    private static final Pattern EVIDENCE_LINE = Pattern.compile("^\\[\\d+].+");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?%?");
    private static final Pattern NEGATION_PATTERN = Pattern.compile(
            "(?:不|没|未|否|禁止|不能|不可|无需|无法|并非|不是)");

    private final EmbeddingModel embeddingModel;
    private final AiProperties aiProperties;

    @Override
    public AnswerSupportResult verify(String question, String answer, String evidenceContext) {
        AiProperties.Adaptive config = aiProperties.getRag().getAdaptive();
        if (!config.isAnswerSupportEnabled()) {
            return AnswerSupportResult.skipped();
        }
        List<String> claims = extractClaims(answer);
        List<String> evidence = extractEvidence(evidenceContext);
        if (claims.isEmpty() || evidence.isEmpty()) {
            return unsupported("answer claims or evidence are empty", claims.size(), 0, 0.0, false);
        }

        Set<String> answerNumbers = extractNumbers(answer);
        boolean numbersSupported = extractNumbers(String.join(" ", evidence)).containsAll(answerNumbers);
        if (!numbersSupported) {
            return unsupported("answer contains numbers absent from evidence", claims.size(), 0, 0.0, false);
        }
        List<TextSegment> inputs = new ArrayList<>(claims.size() + evidence.size());
        claims.forEach(claim -> inputs.add(TextSegment.from(claim)));
        evidence.forEach(item -> inputs.add(TextSegment.from(item)));

        List<Embedding> embeddings;
        try {
            embeddings = embeddingModel.embedAll(inputs).content();
        } catch (RuntimeException exception) {
            throw new KnowledgeRetrievalUnavailableException(
                    "Answer support verification is temporarily unavailable", exception);
        }
        if (embeddings == null || embeddings.size() != inputs.size()) {
            throw new KnowledgeRetrievalUnavailableException(
                    "Answer support verification returned an invalid embedding response");
        }

        int supportedClaims = 0;
        double scoreSum = 0.0;
        for (int claimIndex = 0; claimIndex < claims.size(); claimIndex++) {
            double bestScore = 0.0;
            for (int evidenceIndex = 0; evidenceIndex < evidence.size(); evidenceIndex++) {
                if (!hasCompatiblePolarity(claims.get(claimIndex), evidence.get(evidenceIndex))) {
                    continue;
                }
                double score = clamp(CosineSimilarity.between(
                        embeddings.get(claimIndex),
                        embeddings.get(claims.size() + evidenceIndex)));
                bestScore = Math.max(bestScore, score);
            }
            scoreSum += bestScore;
            if (bestScore >= config.getAnswerSupportMinScore()) {
                supportedClaims++;
            }
        }

        double averageScore = scoreSum / claims.size();
        double supportedRatio = (double) supportedClaims / claims.size();
        boolean supported = numbersSupported
                && supportedRatio >= config.getAnswerSupportMinRatio()
                && averageScore >= config.getAnswerSupportMinScore();
        String reason = "avgSemantic=" + format(averageScore)
                + ", supportedClaims=" + supportedClaims + "/" + claims.size()
                + ", ratio=" + format(supportedRatio)
                + ", numbersSupported=" + numbersSupported;

        return AnswerSupportResult.builder()
                .supported(supported)
                .score(averageScore)
                .supportedClaimRatio(supportedRatio)
                .claimCount(claims.size())
                .supportedClaimCount(supportedClaims)
                .numbersSupported(numbersSupported)
                .reason(reason)
                .build();
    }

    private List<String> extractClaims(String answer) {
        if (!StringUtils.hasText(answer)) {
            return List.of();
        }
        List<String> claims = new ArrayList<>();
        for (String candidate : CLAIM_SEPARATOR.split(answer.trim())) {
            String claim = candidate.trim();
            if (claim.codePointCount(0, claim.length()) >= 4) {
                claims.add(claim);
            }
        }
        return claims;
    }

    private List<String> extractEvidence(String context) {
        if (!StringUtils.hasText(context)) {
            return List.of();
        }
        List<String> evidence = new ArrayList<>();
        for (String line : context.split("\\R")) {
            String candidate = line.trim();
            if (EVIDENCE_LINE.matcher(candidate).matches()) {
                String content = candidate.replaceFirst("^\\[\\d+]\\s*", "");
                for (String unit : EVIDENCE_SEPARATOR.split(content)) {
                    String normalized = unit.trim();
                    if (normalized.codePointCount(0, normalized.length()) >= 2) {
                        evidence.add(normalized);
                    }
                }
            }
        }
        return evidence;
    }

    private Set<String> extractNumbers(String text) {
        Set<String> numbers = new LinkedHashSet<>();
        if (text == null) {
            return numbers;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
        return numbers;
    }

    private boolean hasCompatiblePolarity(String claim, String evidence) {
        return NEGATION_PATTERN.matcher(claim).find() == NEGATION_PATTERN.matcher(evidence).find();
    }

    private AnswerSupportResult unsupported(String reason,
                                            int claimCount,
                                            int supportedClaimCount,
                                            double score,
                                            boolean numbersSupported) {
        return AnswerSupportResult.builder()
                .supported(false)
                .score(score)
                .supportedClaimRatio(claimCount == 0 ? 0.0 : (double) supportedClaimCount / claimCount)
                .claimCount(claimCount)
                .supportedClaimCount(supportedClaimCount)
                .numbersSupported(numbersSupported)
                .reason(reason)
                .build();
    }

    private double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String format(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
