package com.aiagent.infrastructure.memory;

import com.aiagent.infrastructure.config.AiProperties;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LongContextManager {

    private static final String HISTORY_PREFIX = "ai:history:";
    private static final String SUMMARY_PREFIX = "ai:summary:";
    private static final String TURN_COUNT_PREFIX = "ai:turns:";
    private static final int DEFAULT_SUMMARY_INTERVAL = 5;
    private static final int DEFAULT_SLIDING_WINDOW_SIZE = 10;
    private static final int SUMMARY_RETRIEVE_TOP_K = 3;
    private static final int MAX_SUMMARY_ENTRIES = 20;
    private static final double SUMMARY_MIN_SIMILARITY = 0.18;

    private static final String SUMMARY_PROMPT = """
            请将以下对话历史压缩为一段简洁的摘要（不超过 200 字）：
            保留关键信息：用户意图、已解决的问题、未解决的问题。

            {{conversation}}

            摘要：
            """;

    private final ChatLanguageModel chatLanguageModel;
    private final EmbeddingModel embeddingModel;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AiProperties aiProperties;

    public String getOptimizedContext(String sessionId, String question) {
        StringBuilder context = new StringBuilder();

        String recentHistory = getSlidingWindowHistory(sessionId);
        if (!recentHistory.isEmpty()) {
            context.append("【最近对话】\n").append(recentHistory).append("\n\n");
        }

        String relevantSummaries = retrieveRelevantSummaries(sessionId, question);
        if (!relevantSummaries.isEmpty()) {
            context.append("【相关历史摘要】\n").append(relevantSummaries).append("\n\n");
        }

        return context.toString();
    }

    @SuppressWarnings("unchecked")
    public void saveMessageAndMaybeSummarize(String sessionId, String role, String content) {
        String historyKey = HISTORY_PREFIX + sessionId;
        List<Map<String, String>> messages = (List<Map<String, String>>) redisTemplate.opsForValue().get(historyKey);
        if (messages == null) {
            messages = new ArrayList<>();
        }

        messages.add(Map.of("role", role, "content", content));

        int windowSize = aiProperties.getSession().getSlidingWindowSize() > 0
                ? aiProperties.getSession().getSlidingWindowSize()
                : DEFAULT_SLIDING_WINDOW_SIZE;
        while (messages.size() > windowSize) {
            messages.remove(0);
        }

        redisTemplate.opsForValue().set(historyKey, messages, aiProperties.getSession().getTtl(), TimeUnit.SECONDS);

        // Counted separately from the history list, whose size is capped by the sliding window.
        String turnCountKey = TURN_COUNT_PREFIX + sessionId;
        Long totalTurns = redisTemplate.opsForValue().increment(turnCountKey);
        redisTemplate.expire(turnCountKey, aiProperties.getSession().getTtl(), TimeUnit.SECONDS);

        int summaryInterval = aiProperties.getSession().getSummaryInterval() > 0
                ? aiProperties.getSession().getSummaryInterval()
                : DEFAULT_SUMMARY_INTERVAL;
        if (totalTurns != null && totalTurns % summaryInterval == 0 && totalTurns >= summaryInterval) {
            generateAndStoreSummary(sessionId, messages, summaryInterval, totalTurns);
        }
    }

    @SuppressWarnings("unchecked")
    private String getSlidingWindowHistory(String sessionId) {
        String historyKey = HISTORY_PREFIX + sessionId;
        List<Map<String, String>> messages = (List<Map<String, String>>) redisTemplate.opsForValue().get(historyKey);
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        return messages.stream()
                .map(message -> message.get("role") + ": " + message.get("content"))
                .collect(Collectors.joining("\n"));
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> getHistoryMessages(String sessionId) {
        String historyKey = HISTORY_PREFIX + sessionId;
        List<Map<String, String>> messages = (List<Map<String, String>>) redisTemplate.opsForValue().get(historyKey);
        return messages != null ? messages : new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private void generateAndStoreSummary(String sessionId, List<Map<String, String>> messages, int summaryInterval, long totalTurns) {
        try {
            int start = Math.max(0, messages.size() - summaryInterval);
            List<Map<String, String>> recentMessages = messages.subList(start, messages.size());
            String conversationText = recentMessages.stream()
                    .map(message -> message.get("role") + ": " + message.get("content"))
                    .collect(Collectors.joining("\n"));

            Map<String, Object> variables = new HashMap<>();
            variables.put("conversation", conversationText);
            String summary = chatLanguageModel.generate(PromptTemplate.from(SUMMARY_PROMPT).apply(variables).text());

            String summaryKey = SUMMARY_PREFIX + sessionId;
            List<Map<String, Object>> summaries = (List<Map<String, Object>>) redisTemplate.opsForValue().get(summaryKey);
            if (summaries == null) {
                summaries = new ArrayList<>();
            }

            Map<String, Object> summaryEntry = new HashMap<>();
            summaryEntry.put("summary", summary);
            summaryEntry.put("timestamp", System.currentTimeMillis());
            summaryEntry.put("round", totalTurns);

            float[] embedding = embedText(summary);
            if (embedding != null) {
                summaryEntry.put("embedding", embedding);
            }

            summaries.add(summaryEntry);
            while (summaries.size() > MAX_SUMMARY_ENTRIES) {
                summaries.remove(0);
            }

            redisTemplate.opsForValue().set(summaryKey, summaries, aiProperties.getSession().getTtl(), TimeUnit.SECONDS);
            log.info("Generated session summary: sessionId={}, round={}, length={}", sessionId, totalTurns, summary.length());
        } catch (Exception e) {
            log.warn("Summary generation failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String retrieveRelevantSummaries(String sessionId, String question) {
        String summaryKey = SUMMARY_PREFIX + sessionId;
        List<Map<String, Object>> summaries = (List<Map<String, Object>>) redisTemplate.opsForValue().get(summaryKey);
        if (summaries == null || summaries.isEmpty()) {
            return "";
        }

        float[] queryEmbedding = embedText(question);
        List<Map.Entry<Map<String, Object>, Double>> scoredSummaries = new ArrayList<>();
        for (Map<String, Object> summary : summaries) {
            String text = (String) summary.get("summary");
            if (text == null || text.isBlank()) {
                continue;
            }
            double score = semanticScore(question, text, summary.get("embedding"), queryEmbedding);
            scoredSummaries.add(new AbstractMap.SimpleEntry<>(summary, score));
        }

        return scoredSummaries.stream()
                .sorted((left, right) -> Double.compare(right.getValue(), left.getValue()))
                .limit(SUMMARY_RETRIEVE_TOP_K)
                .filter(entry -> entry.getValue() >= minimumSummaryScore(entry.getKey().get("embedding")))
                .map(entry -> (String) entry.getKey().get("summary"))
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n---\n"));
    }

    private float[] embedText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return embeddingModel.embed(text).content().vector();
        } catch (Exception e) {
            log.warn("Embedding generation failed in LongContextManager: {}", e.getMessage());
            return null;
        }
    }

    private double semanticScore(String question, String summaryText, Object storedEmbedding, float[] queryEmbedding) {
        if (queryEmbedding != null && storedEmbedding instanceof float[]) {
            return cosineSimilarity(queryEmbedding, (float[]) storedEmbedding);
        }
        return keywordMatchScore(question, summaryText);
    }

    private double minimumSummaryScore(Object storedEmbedding) {
        return storedEmbedding instanceof float[] ? SUMMARY_MIN_SIMILARITY : 1.0;
    }

    private double keywordMatchScore(String question, String summaryText) {
        Set<String> keywords = extractKeywords(question);
        if (keywords.isEmpty() || summaryText == null || summaryText.isBlank()) {
            return 0;
        }

        double score = 0;
        String normalizedSummary = summaryText.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (normalizedSummary.contains(keyword.toLowerCase(Locale.ROOT))) {
                score++;
            }
        }
        return score;
    }

    private Set<String> extractKeywords(String question) {
        Set<String> keywords = new HashSet<>();
        String[] parts = question.split("\\s+|[,，。！？、】【；;：“”\"'（）()]");
        for (String part : parts) {
            String candidate = part.trim();
            if (candidate.length() >= 2) {
                keywords.add(candidate);
            }
            if (candidate.length() >= 4) {
                for (int windowSize = 2; windowSize <= Math.min(candidate.length(), 4); windowSize++) {
                    for (int index = 0; index + windowSize <= candidate.length(); index++) {
                        keywords.add(candidate.substring(index, index + windowSize));
                    }
                }
            }
        }
        return keywords;
    }

    private double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length) {
            return 0;
        }

        double dotProduct = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dotProduct += (double) left[index] * right[index];
            leftNorm += (double) left[index] * left[index];
            rightNorm += (double) right[index] * right[index];
        }

        double magnitude = Math.sqrt(leftNorm) * Math.sqrt(rightNorm);
        return magnitude == 0 ? 0 : dotProduct / magnitude;
    }

    public void clearSession(String sessionId) {
        redisTemplate.delete(HISTORY_PREFIX + sessionId);
        redisTemplate.delete(SUMMARY_PREFIX + sessionId);
        redisTemplate.delete(TURN_COUNT_PREFIX + sessionId);
    }
}
