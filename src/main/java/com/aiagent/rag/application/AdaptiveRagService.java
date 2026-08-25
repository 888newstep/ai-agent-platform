package com.aiagent.rag.application;

    import com.aiagent.infrastructure.config.AiProperties;
    import com.aiagent.infrastructure.metrics.PlatformMetricsService;
    import com.aiagent.knowledge.domain.RetrievalChunk;
    import com.aiagent.shared.exception.KnowledgeRetrievalUnavailableException;
    import io.micrometer.core.instrument.Timer;
    import lombok.RequiredArgsConstructor;
    import lombok.extern.slf4j.Slf4j;
    import org.springframework.stereotype.Service;
    import org.springframework.util.StringUtils;

    import java.util.ArrayList;
    import java.util.List;
    import java.util.Map;
    import java.util.Objects;

    /**
     * Orchestrates the adaptive RAG pipeline:
     * route -> rewrite -> retrieve -> verify -> assemble final context.
     */
    @Slf4j
    @Service
    @RequiredArgsConstructor
    public class AdaptiveRagService {

        private static final String TERMINAL_REASON_DIRECT_ANSWER_ROUTE = "direct_answer_route";
        private static final String TERMINAL_REASON_VERIFICATION_HIGH = "verification_high";
        private static final String TERMINAL_REASON_VERIFICATION_INSUFFICIENT = "verification_insufficient";
        private static final String TERMINAL_REASON_MAX_ROUNDS_REACHED = "max_rounds_reached";
        private static final String END_REASON_NO_EVIDENCE = "no_evidence";
        private static final String END_REASON_MAX_ROUNDS_REACHED_PARTIAL = "max_rounds_reached_partial";

        private final AiProperties aiProperties;
        private final MultiRecallService multiRecallService;
        private final QueryRouter queryRouter;
        private final QueryRewriter queryRewriter;
        private final SelfRagVerifier selfRagVerifier;
        private final EvidenceReranker evidenceReranker;
        private final PlatformMetricsService metricsService;

        public AdaptiveRagContext resolve(String question) {
            Timer.Sample sample = metricsService.startSample();
            boolean success = false;
            AdaptiveRagContext context = AdaptiveRagContext.empty(question);

            try {
                if (shouldBypassAdaptive(question)) {
                    success = true;
                    recordAdaptiveMetrics(RagRouteType.DIRECT_ANSWER.name(), RagVerificationLevel.NONE.name(), false, 0, 0, context.getEndReason(), true, sample);
                    return context;
                }

                AdaptiveRagDecision decision = queryRouter.route(question);
                if (decision.getRouteType() == RagRouteType.DIRECT_ANSWER) {
                    context = buildDirectAnswerContext(question, decision);
                    success = true;
                    recordAdaptiveMetrics(decision.getRouteType().name(), RagVerificationLevel.NONE.name(), false, 0, 0, context.getEndReason(), true, sample);
                    return context;
                }

                AdaptiveRagRewriteResult rewriteResult = null;
                AdaptiveRagVerificationResult verificationResult = null;
                List<RetrievalChunk> chunks = List.of();
                int maxRounds = resolveMaxRounds(decision);
                int actualRounds = 0;
                List<AdaptiveRagRoundTrace> roundTraces = new ArrayList<>();

                for (int attempt = 1; attempt <= maxRounds; attempt++) {
                    actualRounds = attempt;
                    rewriteResult = queryRewriter.rewrite(question, decision, verificationResult);
                    chunks = multiRecallService.search(rewriteResult.getRewrittenQuery(), aiProperties.getRag().getTopK());
                    chunks = evidenceReranker.rerank(rewriteResult.getRewrittenQuery(), chunks);
                    verificationResult = selfRagVerifier.verify(question, rewriteResult, chunks, decision.getRouteType());

                    boolean terminal = isTerminal(verificationResult, attempt, maxRounds);
                    String terminalReason = determineTerminalReason(verificationResult, terminal);
                    roundTraces.add(buildRoundTrace(attempt, rewriteResult, chunks, verificationResult, terminal, terminalReason));

                    if (terminal) {
                        break;
                    }
                }
                // resolveMaxRounds guarantees >= 1 iteration, so verificationResult is always assigned
                verificationResult = Objects.requireNonNull(verificationResult);

                String endReason = determineEndReason(verificationResult);
                context = buildContext(question, decision, rewriteResult, verificationResult, chunks, actualRounds, roundTraces, endReason);
                log.debug("Adaptive RAG resolved: route={}, verification={}, rounds={}, chunks={}, rewritten={}",
                        decision.getRouteType(),
                        verificationResult.getLevel(),
                        actualRounds,
                        chunks.size(),
                        rewriteResult != null && rewriteResult.isChanged());

                success = true;
                recordAdaptiveMetrics(
                        decision.getRouteType().name(),
                        verificationResult.getLevel().name(),
                        rewriteResult != null && rewriteResult.isChanged(),
                        actualRounds,
                        chunks.size(),
                        endReason,
                        true,
                        sample
                );
                return context;
            } catch (KnowledgeRetrievalUnavailableException exception) {
                recordAdaptiveMetrics("ERROR", RagVerificationLevel.NONE.name(), false, 0, 0,
                        "retrieval_unavailable", false, sample);
                throw exception;
            } catch (Exception e) {
                log.warn("Adaptive RAG failed for question: {}", e.getMessage());
                recordAdaptiveMetrics("ERROR", RagVerificationLevel.NONE.name(), false, 0, 0, context.getEndReason(), false, sample);
                return context;
            } finally {
                if (!success) {
                    log.debug("Adaptive RAG resolved with fallback context for question={}", question);
                }
            }
        }

        private boolean shouldBypassAdaptive(String question) {
            return !aiProperties.getRag().getAdaptive().isEnabled() || !StringUtils.hasText(question);
        }

        private AdaptiveRagContext buildDirectAnswerContext(String question, AdaptiveRagDecision decision) {
            return AdaptiveRagContext.builder()
                    .context("")
                    .routeType(decision.getRouteType())
                    .originalQuery(question)
                    .rewrittenQuery(question)
                    .decisionReason(decision.getReason())
                    .decisionConfidence(decision.getConfidence())
                    .verificationReason("direct answer route")
                    .verificationLevel(RagVerificationLevel.NONE)
                    .retrievalRounds(0)
                    .chunkCount(0)
                    .rewritten(false)
                    .verified(true)
                    .usedAdaptive(true)
                    .roundTraces(List.of())
                    .endReason(TERMINAL_REASON_DIRECT_ANSWER_ROUTE)
                    .build();
        }

        private int resolveMaxRounds(AdaptiveRagDecision decision) {
            int plannedRounds = Math.max(1, decision.getPlannedRetrievalRounds());
            int configuredMaximum = Math.max(1, aiProperties.getRag().getAdaptive().getMaxRetrievalRounds());
            return Math.min(plannedRounds, configuredMaximum);
        }

        private boolean isTerminal(AdaptiveRagVerificationResult verificationResult, int attempt, int maxRounds) {
            return verificationResult.getLevel() == RagVerificationLevel.HIGH || attempt == maxRounds;
        }

        private String determineTerminalReason(AdaptiveRagVerificationResult verificationResult, boolean terminal) {
            if (!terminal) {
                return TERMINAL_REASON_VERIFICATION_INSUFFICIENT;
            }
            return verificationResult.getLevel() == RagVerificationLevel.HIGH
                    ? TERMINAL_REASON_VERIFICATION_HIGH
                    : TERMINAL_REASON_MAX_ROUNDS_REACHED;
        }

        private String determineEndReason(AdaptiveRagVerificationResult verificationResult) {
            if (verificationResult.getLevel() == RagVerificationLevel.HIGH) {
                return TERMINAL_REASON_VERIFICATION_HIGH;
            }
            if (verificationResult.getLevel() == RagVerificationLevel.NONE) {
                return END_REASON_NO_EVIDENCE;
            }
            return END_REASON_MAX_ROUNDS_REACHED_PARTIAL;
        }

        private void recordAdaptiveMetrics(String route,
                                           String verificationLevel,
                                           boolean rewritten,
                                           int rounds,
                                           int chunkCount,
                                           String endReason,
                                           boolean success,
                                           Timer.Sample sample) {
            metricsService.recordAdaptiveRag(route, verificationLevel, rewritten, rounds, chunkCount, endReason, success, sample);
        }

        private AdaptiveRagRoundTrace buildRoundTrace(int round,
                                                      AdaptiveRagRewriteResult rewriteResult,
                                                      List<RetrievalChunk> chunks,
                                                      AdaptiveRagVerificationResult verificationResult,
                                                      boolean terminal,
                                                      String terminalReason) {
            List<AdaptiveRagRoundTrace.ChunkTrace> chunkTraces = new ArrayList<>();
            for (RetrievalChunk chunk : chunks) {
                Map<String, Object> metadata = chunk.getMetadata();
                chunkTraces.add(AdaptiveRagRoundTrace.ChunkTrace.builder()
                        .chunkId(chunk.getId())
                        .qaPairId(metadataText(metadata, "qa_pair_id"))
                        .documentId(metadataText(metadata, "documentId"))
                        .score(chunk.getScore())
                        .category(metadataText(metadata, "category"))
                        .question(metadataText(metadata, "question"))
                        .retrievalSource(metadata != null ? (String) metadata.get("retrievalSource") : null)
                        .vectorRank(metadata != null && metadata.get("vectorRank") instanceof Integer vectorRank ? vectorRank : null)
                        .bm25Rank(metadata != null && metadata.get("bm25Rank") instanceof Integer bm25Rank ? bm25Rank : null)
                        .rrfScore(metadata != null && metadata.get("rrfScore") instanceof Double rrfScore ? rrfScore : null)
                        .semanticRerankScore(metadataNumber(metadata, SemanticEvidenceReranker.SEMANTIC_SCORE_METADATA))
                        .rerankProvider(metadataText(metadata, SemanticEvidenceReranker.RERANK_PROVIDER_METADATA))
                        .rerankMinScore(metadataNumber(metadata, SemanticEvidenceReranker.RERANK_MIN_SCORE_METADATA))
                        .build());
            }

            return AdaptiveRagRoundTrace.builder()
                    .round(round)
                    .rewrittenQuery(rewriteResult.getRewrittenQuery())
                    .keywords(rewriteResult.getKeywords())
                    .queryRewritten(rewriteResult.isChanged())
                    .rewriteReason(rewriteResult.getReason())
                    .retrievedChunks(chunkTraces)
                    .chunkCount(chunks.size())
                    .verificationLevel(verificationResult.getLevel())
                    .verificationScore(verificationResult.getScore())
                    .semanticScore(verificationResult.getSemanticScore())
                    .keywordCoverage(verificationResult.getKeywordCoverage())
                    .evidenceSufficient(verificationResult.isEvidenceSufficient())
                    .matchedKeywords(verificationResult.getMatchedKeywords())
                    .missingKeywords(verificationResult.getMissingKeywords())
                    .verificationReason(verificationResult.getReason())
                    .terminal(terminal)
                    .terminalReason(terminalReason)
                    .build();
        }

        private String metadataText(Map<String, Object> metadata, String key) {
            if (metadata == null || metadata.get(key) == null) {
                return null;
            }
            String value = String.valueOf(metadata.get(key));
            return StringUtils.hasText(value) ? value : null;
        }

        private Double metadataNumber(Map<String, Object> metadata, String key) {
            if (metadata == null || !(metadata.get(key) instanceof Number value)) {
                return null;
            }
            return value.doubleValue();
        }

        private AdaptiveRagContext buildContext(String question,
                                            AdaptiveRagDecision decision,
                                            AdaptiveRagRewriteResult rewriteResult,
                                            AdaptiveRagVerificationResult verificationResult,
                                            List<RetrievalChunk> chunks,
                                            int retrievalRounds,
                                            List<AdaptiveRagRoundTrace> roundTraces,
                                            String endReason) {
        int maxChunks = aiProperties.getRag().getAdaptive().getMaxContextChunks();
        int limit = Math.min(chunks.size(), maxChunks);
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < limit; index++) {
            RetrievalChunk chunk = chunks.get(index);
            builder.append('[')
                    .append(index + 1)
                    .append("] ")
                    .append(chunk.getContent())
                    .append('\n');
        }

        return AdaptiveRagContext.builder()
                .context(builder.toString())
                .routeType(decision.getRouteType())
                .originalQuery(question)
                .rewrittenQuery(rewriteResult == null ? question : rewriteResult.getRewrittenQuery())
                .decisionReason(decision.getReason())
                .decisionConfidence(decision.getConfidence())
                .verificationReason(verificationResult == null ? "unknown" : verificationResult.getReason())
                .verificationLevel(verificationResult == null ? RagVerificationLevel.NONE : verificationResult.getLevel())
                .retrievalRounds(retrievalRounds)
                .chunkCount(chunks.size())
                .rewritten(rewriteResult != null && rewriteResult.isChanged())
                .verified(verificationResult != null && verificationResult.getLevel() != RagVerificationLevel.NONE)
                .usedAdaptive(true)
                .roundTraces(roundTraces)
                .endReason(endReason)
                .build();
    }

}
