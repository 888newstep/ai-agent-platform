package com.aiagent.agent.api;

import com.aiagent.agent.application.AiAgentService;
import com.aiagent.agent.application.ChatExecutionResult;
import com.aiagent.agent.application.MultiAgentExecutionResult;
import com.aiagent.agent.application.MultiAgentService;
import com.aiagent.chat.application.ChatMessageView;
import com.aiagent.rag.application.AdaptiveRagContext;
import com.aiagent.rag.application.AdaptiveRagRoundTrace;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aiagent.infrastructure.cache.SemanticCacheService;
import com.aiagent.infrastructure.idempotency.IdempotencyService;
import com.aiagent.infrastructure.idempotency.IdempotencyService.ClaimStatus;
import com.aiagent.infrastructure.idempotency.PersistentIdempotencyContext;
import com.aiagent.knowledge.application.DocumentService;
import com.aiagent.rag.application.EvaluationReportHistoryService;
import com.aiagent.rag.application.RagEvaluationService;
import com.aiagent.shared.exception.AuthenticationRequiredException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping("/api/v1/agent")
public class AiAgentController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String EVALUATION_NOTE = "chunkSize/chunkOverlap are snapshot-only and require re-ingestion for fair comparison";

    private final AiAgentService aiAgentService;
    private final DocumentService documentService;
    private final SemanticCacheService semanticCacheService;
    private final IdempotencyService idempotencyService;
    private final MultiAgentService multiAgentService;
    private final RagEvaluationService ragEvaluationService;
    private final EvaluationReportHistoryService evaluationReportHistoryService;

    @Autowired
    public AiAgentController(AiAgentService aiAgentService,
                             DocumentService documentService,
                             SemanticCacheService semanticCacheService,
                             IdempotencyService idempotencyService,
                             MultiAgentService multiAgentService,
                             RagEvaluationService ragEvaluationService,
                             EvaluationReportHistoryService evaluationReportHistoryService) {
        this.aiAgentService = aiAgentService;
        this.documentService = documentService;
        this.semanticCacheService = semanticCacheService;
        this.idempotencyService = idempotencyService;
        this.multiAgentService = multiAgentService;
        this.ragEvaluationService = ragEvaluationService;
        this.evaluationReportHistoryService = evaluationReportHistoryService;
    }

    public AiAgentController(AiAgentService aiAgentService,
                             DocumentService documentService,
                             SemanticCacheService semanticCacheService,
                             MultiAgentService multiAgentService,
                             RagEvaluationService ragEvaluationService,
                             EvaluationReportHistoryService evaluationReportHistoryService) {
        this(aiAgentService, documentService, semanticCacheService, null, multiAgentService,
                ragEvaluationService, evaluationReportHistoryService);
    }

    @PostMapping("/session")
    public ResponseEntity<Map<String, String>> createSession(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        String username = requireUsername(authentication);
        String requestHash = requestFingerprint(username, "session-create");
        PersistentIdempotencyContext persistentContext = persistentContext(
                "agent-session-create", idempotencyKey, requestHash);
        Map<String, String> response = executeIdempotent(
                userScopedOperation("agent-session-create", username),
                idempotencyKey,
                requestHash,
                () -> {
                    String sessionId = aiAgentService.createSession(username, persistentContext);
                    return stringResponse(
                            "sessionId", sessionId,
                            "message", "Session created"
                    );
                });
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> clearSession(@PathVariable String sessionId,
                                             Authentication authentication) {
        aiAgentService.clearSession(requireUsername(authentication), sessionId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/session/{sessionId}/messages")
    public ResponseEntity<Map<String, Object>> getSessionMessages(
            @PathVariable String sessionId,
            @RequestParam(defaultValue = "100") int limit,
            Authentication authentication) {
        List<ChatMessageView> messages = aiAgentService.getSessionMessages(
                requireUsername(authentication), sessionId, limit);
        return ResponseEntity.ok(objectResponse(
                "sessionId", sessionId,
                "count", messages.size(),
                "messages", messages
        ));
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(
            @RequestParam String sessionId,
            @RequestParam String question,
            @RequestParam(defaultValue = "true") boolean useRag,
            @RequestParam(defaultValue = "false") boolean explain,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        String username = requireUsername(authentication);
        String requestHash = requestFingerprint(username, "normal", sessionId, question,
                Boolean.toString(useRag), Boolean.toString(explain));
        PersistentIdempotencyContext persistentContext = persistentContext(
                "agent-chat", idempotencyKey, requestHash);
        Map<String, Object> response = executeIdempotent(
                userScopedOperation("agent-chat", username),
                idempotencyKey,
                requestHash,
                () -> {
                    if (!explain) {
                        return buildChatResponse(
                                sessionId,
                                question,
                                aiAgentService.chat(
                                        username, sessionId, question, useRag, persistentContext),
                                "normal"
                        );
                    }

                    ChatExecutionResult result = aiAgentService.chatDetailed(
                            username, sessionId, question, useRag, persistentContext);
                    return buildChatResponse(sessionId, question, result, "normal");
                });
        return ResponseEntity.ok(response);
    }

    @PostMapping("/react/chat")
    public ResponseEntity<Map<String, Object>> reactChat(
            @RequestParam String sessionId,
            @RequestParam String question,
            @RequestParam(defaultValue = "true") boolean useRag,
            @RequestParam(defaultValue = "false") boolean explain,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        String username = requireUsername(authentication);
        String requestHash = requestFingerprint(username, "react", sessionId, question,
                Boolean.toString(useRag), Boolean.toString(explain));
        PersistentIdempotencyContext persistentContext = persistentContext(
                "agent-react-chat", idempotencyKey, requestHash);
        Map<String, Object> response = executeIdempotent(
                userScopedOperation("agent-react-chat", username),
                idempotencyKey,
                requestHash,
                () -> {
                    ChatExecutionResult result = aiAgentService.reactChatDetailed(
                            username, sessionId, question, useRag, persistentContext);
                    if (!explain) {
                        return buildChatResponse(sessionId, question, result.getAnswer(), "react");
                    }
                    return buildChatResponse(sessionId, question, result, "react");
                });
        return ResponseEntity.ok(response);
    }

    @PostMapping("/multi-agent/execute")
    public ResponseEntity<Map<String, Object>> multiAgentExecute(
            @RequestParam String task,
            @RequestParam(defaultValue = "") String context,
            @RequestParam(defaultValue = "false") boolean explain) {
        MultiAgentExecutionResult result = multiAgentService.executeDetailed(task, context);
        Map<String, Object> response = objectResponse(
                "task", task,
                "answer", result.getAnswer(),
                "mode", "multi-agent"
        );
        if (explain) {
            response.put("explain", result.getTrace());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(
            @RequestParam String sessionId,
            @RequestParam String question,
            @RequestParam(defaultValue = "true") boolean useRag,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        String username = requireUsername(authentication);
        if (!StringUtils.hasText(idempotencyKey)) {
            return aiAgentService.streamChat(username, sessionId, question, useRag);
        }
        if (idempotencyService == null) {
            throw new IllegalStateException("Idempotency service is unavailable");
        }

        String operation = userScopedOperation("agent-stream-chat", username);
        String requestHash = idempotencyService.fingerprint(
                username, "stream", sessionId, question, Boolean.toString(useRag));
        PersistentIdempotencyContext persistentContext = persistentContext(
                "agent-stream-chat", idempotencyKey, requestHash);
        var claim = idempotencyService.claim(operation, idempotencyKey, requestHash);
        if (claim.status() == ClaimStatus.COMPLETED) {
            return Flux.just(idempotencyService.readCompleted(claim.payload(), String.class));
        }
        if (claim.status() == ClaimStatus.IN_PROGRESS) {
            return Flux.just(idempotencyService.awaitCompleted(
                    operation, idempotencyKey, requestHash, String.class));
        }

        StringBuilder answer = new StringBuilder();
        return aiAgentService.streamChat(
                        username, sessionId, question, useRag, persistentContext)
                .doOnNext(answer::append)
                .doOnComplete(() -> idempotencyService.complete(
                        operation, idempotencyKey, requestHash, claim.ownerToken(), answer.toString()))
                .doOnError(error -> idempotencyService.release(
                        operation, idempotencyKey, requestHash, claim.ownerToken()))
                .doOnCancel(() -> idempotencyService.release(
                        operation, idempotencyKey, requestHash, claim.ownerToken()));
    }


    @PostMapping("/rag/debug")
    public ResponseEntity<AdaptiveRagContext> debugAdaptiveRag(
            @RequestParam String question,
            @RequestParam(defaultValue = "true") boolean useRag) {
        return ResponseEntity.ok(aiAgentService.inspectAdaptiveRag(question, useRag));
    }

    @PostMapping("/document/upload")
    public ResponseEntity<Map<String, Object>> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        var document = StringUtils.hasText(idempotencyKey)
                ? documentService.uploadDocument(file, idempotencyKey)
                : documentService.uploadDocument(file);
        return ResponseEntity.accepted().body(objectResponse(
                "message", "Document accepted and queued for async ingestion",
                "documentId", document.getId(),
                "fileName", document.getFileName(),
                "status", document.getProcessingStatus()
        ));
    }

    public ResponseEntity<Map<String, Object>> uploadDocument(MultipartFile file) {
        return uploadDocument(file, null);
    }

    @GetMapping("/document/{documentId}/status")
    public ResponseEntity<Map<String, Object>> getDocumentStatus(@PathVariable Long documentId) {
        return ResponseEntity.ok(documentService.getDocumentStatus(documentId));
    }

    @GetMapping("/document/readiness")
    public ResponseEntity<Map<String, Object>> getKnowledgeReadiness() {
        return ResponseEntity.ok(documentService.getKnowledgeReadiness());
    }

    @PostMapping("/document/{documentId}/retry")
    public ResponseEntity<Map<String, Object>> retryDocument(@PathVariable Long documentId) {
        var document = documentService.retryDocument(documentId);
        return ResponseEntity.accepted().body(objectResponse(
                "message", "Document retry accepted",
                "documentId", document.getId(),
                "status", document.getProcessingStatus()
        ));
    }

    @DeleteMapping("/document/{documentId}")
    public ResponseEntity<Map<String, Object>> deleteDocument(@PathVariable Long documentId) {
        documentService.deleteDocument(documentId);
        return ResponseEntity.ok(objectResponse(
                "message", "Document deleted",
                "documentId", documentId
        ));
    }

    @PostMapping("/document/search")
    public ResponseEntity<Map<String, Object>> searchDocuments(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(defaultValue = "0.7") double threshold) {
        var chunks = documentService.searchSimilar(query, topK, threshold);
        return ResponseEntity.ok(objectResponse(
                "query", query,
                "results", chunks,
                "count", chunks.size()
        ));
    }

    @DeleteMapping("/cache")
    public ResponseEntity<Map<String, String>> clearCache() {
        semanticCacheService.clear();
        return ResponseEntity.ok(stringResponse("message", "Semantic cache cleared"));
    }

    @PostMapping("/evaluate")
    public ResponseEntity<Map<String, Object>> evaluateRag(
            @RequestParam(defaultValue = "1,3,5,10") String topKs,
            @RequestParam(required = false) String datasetPath,
            @RequestParam(required = false) Double similarityThreshold,
            @RequestParam(required = false) Boolean hybridSearch) {
        List<Integer> kValues = parseTopKs(topKs);
        RagEvaluationService.EvaluationReport report = StringUtils.hasText(datasetPath)
                ? ragEvaluationService.evaluateFromFile(datasetPath, kValues, similarityThreshold, hybridSearch)
                : ragEvaluationService.quickEvaluate(kValues, similarityThreshold, hybridSearch);
        return ResponseEntity.ok(buildEvaluationResponse(report));
    }

    @PostMapping("/evaluate/compare")
    public ResponseEntity<Map<String, Object>> compareRag(
            @RequestParam String datasetPath,
            @RequestParam(defaultValue = "1,3,5,10") String topKs,
            @RequestParam(required = false) String profilesJson) {
        var report = ragEvaluationService.compareFromFile(datasetPath, parseTopKs(topKs), parseProfiles(profilesJson));
        return ResponseEntity.ok(buildComparisonResponse(report));
    }

    @PostMapping("/evaluate/export")
    public ResponseEntity<Map<String, Object>> exportEvaluation(
            @RequestParam(defaultValue = "1,3,5,10") String topKs,
            @RequestParam(required = false) String datasetPath,
            @RequestParam(required = false) Double similarityThreshold,
            @RequestParam(required = false) Boolean hybridSearch) {
        List<Integer> kValues = parseTopKs(topKs);
        RagEvaluationService.EvaluationReport report = StringUtils.hasText(datasetPath)
                ? ragEvaluationService.evaluateFromFile(datasetPath, kValues, similarityThreshold, hybridSearch)
                : ragEvaluationService.quickEvaluate(kValues, similarityThreshold, hybridSearch);

        String generatedAt = Instant.now().toString();
        Map<String, Object> reportPayload = buildEvaluationResponse(report);
        reportPayload.put("generatedAt", generatedAt);
        try {
            EvaluationReportHistoryService.SavedReport savedReport = evaluationReportHistoryService.save(reportPayload);
            return ResponseEntity.ok(objectResponse(
                    "exported", true,
                    "fileName", savedReport.fileName(),
                    "generatedAt", generatedAt,
                    "report", reportPayload
            ));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to export evaluation report", ex);
        }
    }

    @GetMapping("/evaluate/history")
    public ResponseEntity<Map<String, Object>> evaluationHistory(
            @RequestParam(defaultValue = "20") int limit) {
        if (limit <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be greater than zero");
        }
        try {
            List<Map<String, Object>> reports = evaluationReportHistoryService.list(limit);
            return ResponseEntity.ok(objectResponse(
                    "count", reports.size(),
                    "reports", reports
            ));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to read evaluation report history", ex);
        }
    }

    @GetMapping("/evaluate/history/compare")
    public ResponseEntity<Map<String, Object>> compareEvaluationHistory(
            @RequestParam String baseline,
            @RequestParam String candidate) {
        try {
            return ResponseEntity.ok(evaluationReportHistoryService.compare(baseline, candidate));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to compare evaluation reports", ex);
        }
    }

    private List<Integer> parseTopKs(String topKs) {
        if (!StringUtils.hasText(topKs)) {
            return List.of();
        }

        try {
            return java.util.Arrays.stream(topKs.split(","))
                    .map(s -> s.trim())
                    .filter(StringUtils::hasText)
                    .map(Integer::parseInt)
                    .toList();
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid topKs: " + topKs, ex);
        }
    }

    private List<RagEvaluationService.EvaluationProfile> parseProfiles(String profilesJson) {
        if (!StringUtils.hasText(profilesJson)) {
            return List.of();
        }

        try {
            return OBJECT_MAPPER.readValue(
                    profilesJson,
                    new TypeReference<List<RagEvaluationService.EvaluationProfile>>() {
                    }
            );
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid profilesJson payload", ex);
        }
    }

    private Map<String, Object> buildEvaluationResponse(RagEvaluationService.EvaluationReport report) {
        return objectResponse(
                "summary", report.toFormattedSummary(),
                "datasetSource", report.getDatasetSource(),
                "datasetSize", report.getDatasetSize(),
                "topKs", report.getTopKs(),
                "metrics", report.getMetrics(),
                "categoryMetrics", report.getCategoryMetrics(),
                "config", report.getConfigSnapshot(),
                "note", EVALUATION_NOTE
        );
    }

    private Map<String, Object> buildComparisonResponse(RagEvaluationService.ComparisonReport report) {
        Map<String, Object> reports = new LinkedHashMap<>();
        for (Map.Entry<String, RagEvaluationService.EvaluationReport> entry : report.getReports().entrySet()) {
            reports.put(entry.getKey(), buildEvaluationResponse(entry.getValue()));
        }
        return objectResponse(
                "summary", report.toFormattedSummary(),
                "datasetSource", report.getDatasetSource(),
                "datasetSize", report.getDatasetSize(),
                "topKs", report.getTopKs(),
                "reports", reports,
                "note", EVALUATION_NOTE
        );
    }

    private Map<String, Object> buildChatResponse(String sessionId,
                                                  String question,
                                                  String answer,
                                                  String mode) {
        return objectResponse(
                "sessionId", sessionId,
                "question", question,
                "answer", answer,
                "mode", mode
        );
    }

    private <V> Map<String, V> executeIdempotent(String operation,
                                                  String idempotencyKey,
                                                  String requestHash,
                                                  Supplier<Map<String, V>> action) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return action.get();
        }
        if (idempotencyService == null) {
            throw new IllegalStateException("Idempotency service is unavailable");
        }
        return idempotencyService.executeMap(operation, idempotencyKey, requestHash, action);
    }

    private String requestFingerprint(String... values) {
        return idempotencyService == null ? "" : idempotencyService.fingerprint(values);
    }

    private String userScopedOperation(String operation, String username) {
        return operation + ":" + username;
    }

    private PersistentIdempotencyContext persistentContext(String operation,
                                                           String idempotencyKey,
                                                           String requestHash) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return PersistentIdempotencyContext.disabled();
        }
        if (idempotencyService == null) {
            throw new IllegalStateException("Idempotency service is unavailable");
        }
        return new PersistentIdempotencyContext(
                operation,
                idempotencyService.fingerprint(idempotencyKey),
                requestHash);
    }

    private String requireUsername(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !StringUtils.hasText(authentication.getName())) {
            throw new AuthenticationRequiredException("Authentication required");
        }
        return authentication.getName();
    }

    private Map<String, Object> buildChatResponse(String sessionId,
                                                  String question,
                                                  ChatExecutionResult result,
                                                  String mode) {
        Map<String, Object> response = buildChatResponse(sessionId, question, result.getAnswer(), mode);
        response.put("explain", buildExplainPayload(result));
        return response;
    }

    private Map<String, Object> buildExplainPayload(ChatExecutionResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("responseSource", result.getResponseSource());
        payload.put("cacheHit", result.isCacheHit());

        AdaptiveRagContext context = result.getAdaptiveRagContext();
        payload.put("adaptiveEvaluated", context != null && context.isUsedAdaptive());
        payload.put("routeType", context == null || context.getRouteType() == null
                ? null : context.getRouteType().name());
        payload.put("rewrittenQuery", context == null ? null : context.getRewrittenQuery());
        payload.put("decisionReason", context == null ? null : context.getDecisionReason());
        payload.put("decisionConfidence", context == null ? null : context.getDecisionConfidence());
        payload.put("verificationLevel", context == null || context.getVerificationLevel() == null
                ? null : context.getVerificationLevel().name());
        payload.put("verificationReason", context == null ? null : context.getVerificationReason());
        payload.put("retrievalRounds", context == null ? 0 : context.getRetrievalRounds());
        payload.put("chunkCount", context == null ? 0 : context.getChunkCount());
        payload.put("rewritten", context != null && context.isRewritten());
        payload.put("verified", context != null && context.isVerified());
        payload.put("usedAdaptive", context != null && context.isUsedAdaptive());
        payload.put("endReason", context == null ? null : context.getEndReason());
        payload.put("roundTraces", context == null || context.getRoundTraces() == null
                ? List.of() : context.getRoundTraces());
        payload.put("evidence", context == null ? List.of() : buildEvidence(context));
        payload.put("reactTrace", result.getReactTrace());
        return payload;
    }

    private List<Map<String, Object>> buildEvidence(AdaptiveRagContext context) {
        if (context.getRoundTraces() == null || context.getRoundTraces().isEmpty()) {
            return List.of();
        }

        List<Map<String, Object>> evidence = new java.util.ArrayList<>();
        for (AdaptiveRagRoundTrace roundTrace : context.getRoundTraces()) {
            if (roundTrace.getRetrievedChunks() == null || roundTrace.getRetrievedChunks().isEmpty()) {
                continue;
            }
            for (AdaptiveRagRoundTrace.ChunkTrace chunk : roundTrace.getRetrievedChunks()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("round", roundTrace.getRound());
                item.put("rewrittenQuery", roundTrace.getRewrittenQuery());
                item.put("verificationLevel", roundTrace.getVerificationLevel() == null ? null : roundTrace.getVerificationLevel().name());
                item.put("terminal", roundTrace.isTerminal());
                item.put("terminalReason", roundTrace.getTerminalReason());
                item.put("chunkId", chunk.getChunkId());
                item.put("score", chunk.getScore());
                item.put("retrievalSource", chunk.getRetrievalSource());
                item.put("vectorRank", chunk.getVectorRank());
                item.put("bm25Rank", chunk.getBm25Rank());
                item.put("rrfScore", chunk.getRrfScore());
                evidence.add(item);
            }
        }
        return evidence;
    }

    private Map<String, String> stringResponse(String... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("stringResponse requires key/value pairs");
        }
        Map<String, String> response = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            response.put(entries[index], entries[index + 1]);
        }
        return response;
    }

    private Map<String, Object> objectResponse(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("objectResponse requires key/value pairs");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            response.put((String) entries[index], entries[index + 1]);
        }
        return response;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(stringResponse(
                "status", "UP",
                "service", "AI Customer Service Agent",
                "version", "1.0.0"
        ));
    }

    @GetMapping({"/", "/admin"})
    public String adminPage() {
        return "redirect:/admin.html";
    }
}
