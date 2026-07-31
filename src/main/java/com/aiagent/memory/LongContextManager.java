package com.aiagent.memory;

import com.aiagent.config.AiProperties;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 长上下文管理器（Q174 面试考点）
 *
 * <p>核心策略（三层递进）：
 * <ol>
 *   <li><b>滑动窗口</b>：只保留最近 N 轮对话，超出部分移入历史存档</li>
 *   <li><b>摘要压缩</b>：每 5 轮对话生成一次摘要，压缩历史对话存入向量库</li>
 *   <li><b>历史检索</b>：从向量库中检索与当前问题最相关的历史摘要，注入上下文</li>
 * </ol>
 *
 * <p>面试价值：
 * <ul>
 *   <li>Q174：Agent 如何处理长上下文？</li>
 *   <li>展示"检索+压缩"的经典记忆管理方案</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LongContextManager {

    private final ChatLanguageModel chatLanguageModel;
    private final RedisTemplate<String, Object> redisTemplate;
    private final AiProperties aiProperties;

    /** 会话历史前缀 */
    private static final String HISTORY_PREFIX = "ai:history:";
    /** 摘要前缀 */
    private static final String SUMMARY_PREFIX = "ai:summary:";
    /** 摘要生成间隔（每 N 轮生成一次摘要） */
    private static final int SUMMARY_INTERVAL = 5;
    /** 滑动窗口大小（保留最近 N 轮对话） */
    private static final int SLIDING_WINDOW_SIZE = 10;
    /** 摘要检索返回条数 */
    private static final int SUMMARY_RETRIEVE_TOP_K = 3;

    private static final String SUMMARY_PROMPT = """
            请将以下对话历史压缩为一段简洁的摘要（不超过 200 字），
            保留关键信息（用户意图、已解决的问题、未解决的问题）：
            
            {{conversation}}
            
            摘要：
            """;

    /**
     * 获取优化后的上下文（滑动窗口 + 摘要检索）
     *
     * @param sessionId 会话 ID
     * @param question  当前用户问题
     * @return 优化后的上下文字符串
     */
    @SuppressWarnings("unchecked")
    public String getOptimizedContext(String sessionId, String question) {
        StringBuilder context = new StringBuilder();

        // 1. 获取滑动窗口内的最近对话
        String recentHistory = getSlidingWindowHistory(sessionId);
        if (!recentHistory.isEmpty()) {
            context.append("【最近对话】\n").append(recentHistory).append("\n\n");
        }

        // 2. 检索相关历史摘要（向量检索）
        String relevantSummaries = retrieveRelevantSummaries(sessionId, question);
        if (!relevantSummaries.isEmpty()) {
            context.append("【相关历史摘要】\n").append(relevantSummaries).append("\n\n");
        }

        return context.toString();
    }

    /**
     * 保存消息并触发摘要生成（每 5 轮生成一次）
     */
    @SuppressWarnings("unchecked")
    public void saveMessageAndMaybeSummarize(String sessionId, String role, String content) {
        String historyKey = HISTORY_PREFIX + sessionId;
        List<Map<String, String>> messages = (List<Map<String, String>>) redisTemplate.opsForValue().get(historyKey);
        if (messages == null) {
            messages = new ArrayList<>();
        }

        messages.add(Map.of("role", role, "content", content));

        // 滑动窗口：超出限制时丢弃最早的消息
        int windowSize = aiProperties.getSession().getSlidingWindowSize() > 0
                ? aiProperties.getSession().getSlidingWindowSize()
                : SLIDING_WINDOW_SIZE;
        while (messages.size() > windowSize) {
            messages.remove(0);
        }

        redisTemplate.opsForValue().set(historyKey, messages,
                aiProperties.getSession().getTtl(), TimeUnit.SECONDS);

        // 检查是否需要生成摘要
        int summaryInterval = aiProperties.getSession().getSummaryInterval() > 0
                ? aiProperties.getSession().getSummaryInterval()
                : SUMMARY_INTERVAL;
        if (messages.size() % summaryInterval == 0 && messages.size() >= summaryInterval) {
            generateAndStoreSummary(sessionId, messages);
        }
    }

    /**
     * 获取滑动窗口内的最近对话
     */
    @SuppressWarnings("unchecked")
    private String getSlidingWindowHistory(String sessionId) {
        String historyKey = HISTORY_PREFIX + sessionId;
        List<Map<String, String>> messages = (List<Map<String, String>>) redisTemplate.opsForValue().get(historyKey);
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        return messages.stream()
                .map(msg -> msg.get("role") + ": " + msg.get("content"))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 获取历史消息原始列表（用于构建完整 prompt）
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> getHistoryMessages(String sessionId) {
        String historyKey = HISTORY_PREFIX + sessionId;
        List<Map<String, String>> messages = (List<Map<String, String>>) redisTemplate.opsForValue().get(historyKey);
        return messages != null ? messages : new ArrayList<>();
    }

    /**
     * 生成摘要并存储到向量库
     */
    private void generateAndStoreSummary(String sessionId, List<Map<String, String>> messages) {
        try {
            // 只对最近一轮对话生成摘要（避免重复）
            int start = Math.max(0, messages.size() - SUMMARY_INTERVAL);
            List<Map<String, String>> recentMessages = messages.subList(start, messages.size());
            String conversationText = recentMessages.stream()
                    .map(msg -> msg.get("role") + ": " + msg.get("content"))
                    .collect(Collectors.joining("\n"));

            Map<String, Object> variables = new HashMap<>();
            variables.put("conversation", conversationText);
            String summary = chatLanguageModel.generate(
                    PromptTemplate.from(SUMMARY_PROMPT).apply(variables).text());

            log.info("生成会话摘要 [sessionId={}, round={}, len={}]",
                    sessionId, messages.size(), summary.length());

            // 存储摘要到 Redis（带时间戳用于检索）
            String summaryKey = SUMMARY_PREFIX + sessionId;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> summaries = (List<Map<String, Object>>) redisTemplate.opsForValue().get(summaryKey);
            if (summaries == null) {
                summaries = new ArrayList<>();
            }
            Map<String, Object> summaryEntry = new HashMap<>();
            summaryEntry.put("summary", summary);
            summaryEntry.put("timestamp", System.currentTimeMillis());
            summaryEntry.put("round", messages.size());
            summaries.add(summaryEntry);

            // 只保留最近 20 条摘要
            while (summaries.size() > 20) {
                summaries.remove(0);
            }
            redisTemplate.opsForValue().set(summaryKey, summaries,
                    aiProperties.getSession().getTtl(), TimeUnit.SECONDS);

        } catch (Exception e) {
            log.warn("摘要生成失败: {}", e.getMessage());
        }
    }

    /**
     * 检索与当前问题相关的历史摘要（基于关键词匹配）
     *
     * 注意：这里使用简单的关键词匹配检索摘要。
     * 生产环境应接入 Embedding 向量检索实现语义匹配。
     */
    @SuppressWarnings("unchecked")
    private String retrieveRelevantSummaries(String sessionId, String question) {
        String summaryKey = SUMMARY_PREFIX + sessionId;
        List<Map<String, Object>> summaries = (List<Map<String, Object>>) redisTemplate.opsForValue().get(summaryKey);
        if (summaries == null || summaries.isEmpty()) {
            return "";
        }

        // 提取问题关键词
        Set<String> keywords = extractKeywords(question);

        // 评分：按关键词匹配度排序
        List<Map.Entry<Map<String, Object>, Double>> scored = new ArrayList<>();
        for (Map<String, Object> summary : summaries) {
            String text = (String) summary.get("summary");
            if (text == null) continue;
            double score = 0;
            for (String keyword : keywords) {
                if (text.toLowerCase().contains(keyword.toLowerCase())) {
                    score++;
                }
            }
            scored.add(new AbstractMap.SimpleEntry<>(summary, score));
        }

        // 按分数降序，取 topK
        return scored.stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(SUMMARY_RETRIEVE_TOP_K)
                .filter(e -> e.getValue() > 0)
                .map(e -> (String) e.getKey().get("summary"))
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n---\n"));
    }

    /**
     * 从问题中提取关键词（简单分词）
     */
    private Set<String> extractKeywords(String question) {
        Set<String> keywords = new HashSet<>();
        // 按标点和空格分词
        String[] parts = question.split("\\s+|[,，。！？、；;：:\\\"''【】（）()]");
        for (String part : parts) {
            part = part.trim();
            if (part.length() >= 2) {
                keywords.add(part);
            }
        }
        return keywords;
    }

    /**
     * 清除会话历史
     */
    public void clearSession(String sessionId) {
        redisTemplate.delete(HISTORY_PREFIX + sessionId);
        redisTemplate.delete(SUMMARY_PREFIX + sessionId);
    }
}