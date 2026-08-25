package com.aiagent.rag.application;

import com.aiagent.infrastructure.config.AiProperties;
import com.aiagent.shared.exception.KnowledgeRetrievalUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class HttpCrossEncoderRerankClient implements CrossEncoderRerankClient {

    private final ObjectMapper objectMapper;
    private final AiProperties aiProperties;
    private final HttpClient httpClient;

    public HttpCrossEncoderRerankClient(ObjectMapper objectMapper, AiProperties aiProperties) {
        this.objectMapper = objectMapper;
        this.aiProperties = aiProperties;
        int timeoutMs = Math.max(1, aiProperties.getRag().getAdaptive().getCrossEncoder().getTimeoutMs());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    @Override
    public List<RerankScore> rerank(String query, List<String> documents) {
        AiProperties.CrossEncoder config = aiProperties.getRag().getAdaptive().getCrossEncoder();
        validateConfig(config, query, documents);

        List<String> boundedDocuments = documents.stream()
                .map(document -> truncate(document, config.getMaxDocumentCharacters()))
                .toList();
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", config.getModelName());
        requestBody.put("query", query);
        requestBody.put("documents", boundedDocuments);
        requestBody.put("top_n", boundedDocuments.size());
        requestBody.put("return_documents", false);

        try {
            String payload = objectMapper.writeValueAsString(requestBody);
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.getEndpoint()))
                    .timeout(Duration.ofMillis(config.getTimeoutMs()))
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new KnowledgeRetrievalUnavailableException(
                        "Cross-encoder reranker returned HTTP " + response.statusCode());
            }
            return parseResponse(response.body());
        } catch (KnowledgeRetrievalUnavailableException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new KnowledgeRetrievalUnavailableException(
                    "Cross-encoder reranking was interrupted", exception);
        } catch (IOException | IllegalArgumentException exception) {
            throw new KnowledgeRetrievalUnavailableException(
                    "Cross-encoder reranker is temporarily unavailable", exception);
        }
    }

    private List<RerankScore> parseResponse(String responseBody) throws IOException {
        JsonNode results = objectMapper.readTree(responseBody).path("results");
        if (!results.isArray()) {
            throw new KnowledgeRetrievalUnavailableException(
                    "Cross-encoder reranker returned an invalid response");
        }
        List<RerankScore> scores = new ArrayList<>();
        for (JsonNode result : results) {
            JsonNode index = result.get("index");
            JsonNode relevanceScore = result.get("relevance_score");
            if (index == null || !index.canConvertToInt()
                    || relevanceScore == null || !relevanceScore.isNumber()) {
                throw new KnowledgeRetrievalUnavailableException(
                        "Cross-encoder reranker returned an invalid result");
            }
            scores.add(new RerankScore(index.intValue(), relevanceScore.doubleValue()));
        }
        return scores;
    }

    private void validateConfig(AiProperties.CrossEncoder config,
                                String query,
                                List<String> documents) {
        if (config == null
                || !StringUtils.hasText(config.getEndpoint())
                || !StringUtils.hasText(config.getApiKey())
                || !StringUtils.hasText(config.getModelName())) {
            throw new KnowledgeRetrievalUnavailableException(
                    "Cross-encoder reranker configuration is incomplete");
        }
        URI endpoint;
        try {
            endpoint = URI.create(config.getEndpoint());
        } catch (IllegalArgumentException exception) {
            throw new KnowledgeRetrievalUnavailableException(
                    "Cross-encoder reranker endpoint is invalid", exception);
        }
        if (!("http".equalsIgnoreCase(endpoint.getScheme())
                || "https".equalsIgnoreCase(endpoint.getScheme()))) {
            throw new KnowledgeRetrievalUnavailableException(
                    "Cross-encoder reranker endpoint must use HTTP or HTTPS");
        }
        if (!StringUtils.hasText(query) || documents == null || documents.isEmpty()) {
            throw new IllegalArgumentException("Rerank query and documents must not be empty");
        }
        if (config.getTimeoutMs() <= 0 || config.getMaxDocumentCharacters() <= 0
                || !Double.isFinite(config.getMinScore())
                || config.getMinScore() < 0.0 || config.getMinScore() > 1.0) {
            throw new KnowledgeRetrievalUnavailableException(
                    "Cross-encoder reranker limits or score threshold are invalid");
        }
    }

    private String truncate(String value, int maxCharacters) {
        String text = value == null ? "" : value;
        return text.length() <= maxCharacters ? text : text.substring(0, maxCharacters);
    }
}
