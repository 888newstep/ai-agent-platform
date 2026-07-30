package com.aiagent.agent;

import com.aiagent.tool.ToolService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct Agent — 推理 + 行动循环
 *
 * <p>核心循环：Thought → Action → Observation → Final Answer
 * <br>参考论文：<a href="https://arxiv.org/abs/2210.03629">ReAct: Synergizing Reasoning and Acting in Language Models</a>
 *
 * <p>安全机制：
 * <ul>
 *   <li>最大迭代步数（maxSteps=10）</li>
 *   <li>重复观察检测（same observation >= 3 次则终止）</li>
 *   <li>整体超时控制（3分钟）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReActAgent {

    private final ChatLanguageModel chatLanguageModel;
    private final ToolService toolService;

    /** 最大迭代步数 */
    private static final int MAX_STEPS = 10;

    /** 整体超时 */
    private static final Duration TIMEOUT = Duration.ofMinutes(3);

    /** 重复检测阈值 */
    private static final int MAX_REPEATED_OBSERVATIONS = 3;

    /**
     * ReAct 系统提示词模板
     * 定义了 Thought → Action → Observation → Final Answer 的循环格式
     */
    private static final String SYSTEM_PROMPT = """
            你是一个智能 AI 助手，可以通过思考和调用工具来解决问题。
            
            可用工具：
            %s
            
            你必须严格按照以下格式回应：
            
            Thought: 你的思考过程，分析当前情况并决定下一步做什么
            Action: 工具名称（从上述工具列表中选择）
            Action Input: 工具的输入参数（JSON 格式）
            Observation: 工具执行结果（由系统填充，你不需要生成）
            
            ...（可重复 Thought → Action → Observation 循环）
            
            Final Answer: 你的最终回答
            
            注意事项：
            1. 每次只能调用一个工具
            2. 工具调用后，系统会填充 Observation，你需要基于 Observation 继续思考
            3. 如果已经得到足够的信息，直接输出 Final Answer
            4. 如果工具调用失败，尝试其他方法或直接给出你的判断
            5. 始终用中文回答
            """;

    /** 正则：提取 Action 和 Action Input */
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "Action:\\s*(\\w+)\\s*Action\\s+Input:\\s*(.+?)(?=Thought:|Observation:|Final Answer:|$)",
            Pattern.DOTALL
    );

    /** 正则：提取 Final Answer */
    private static final Pattern FINAL_ANSWER_PATTERN = Pattern.compile(
            "Final Answer:\\s*(.+)", Pattern.DOTALL
    );

    /** 正则：提取 Thought */
    private static final Pattern THOUGHT_PATTERN = Pattern.compile(
            "Thought:\\s*(.+?)(?=Action:|Final Answer:|$)", Pattern.DOTALL
    );

    /**
     * 执行 ReAct 循环
     *
     * @param question 用户问题
     * @param context  RAG 上下文（可选）
     * @return 最终回答
     */
    public String execute(String question, String context) {
        return execute(question, context, "");
    }

    /**
     * 执行 ReAct 循环（带会话历史）
     *
     * @param question 用户问题
     * @param context  RAG 上下文（可选）
     * @param history  会话历史
     * @return 最终回答
     */
    public String execute(String question, String context, String history) {
        Instant startTime = Instant.now();
        List<String> observations = new ArrayList<>();
        List<String> thoughtHistory = new ArrayList<>();

        // 1. 构建初始 prompt
        String toolsDescription = buildToolsDescription();
        String userPrompt = buildUserPrompt(question, context, history);

        // 2. ReAct 主循环
        StringBuilder conversation = new StringBuilder();
        conversation.append(userPrompt);

        for (int step = 0; step < MAX_STEPS; step++) {
            // 超时检查
            if (Duration.between(startTime, Instant.now()).compareTo(TIMEOUT) > 0) {
                log.warn("ReAct Agent 超时，已执行 {} 步", step);
                return "抱歉，处理超时了，请简化您的问题后重试。";
            }

            log.info("ReAct Step {}/{}: 调用 LLM 推理", step + 1, MAX_STEPS);

            // 2a. LLM 推理
            String fullPrompt = String.format(
                    "%s\n\n%s\n\n%s",
                    SYSTEM_PROMPT.replace("%s", toolsDescription),
                    conversation.toString(),
                    "请继续你的思考和行动（如果已有足够信息，请直接输出 Final Answer）："
            );

            String llmOutput;
            try {
                llmOutput = chatLanguageModel.generate(fullPrompt);
            } catch (Exception e) {
                log.error("ReAct Step {} LLM 调用失败: {}", step + 1, e.getMessage());
                return "抱歉，AI 模型调用失败，请稍后重试。错误：" + e.getMessage();
            }

            log.debug("ReAct Step {} LLM 输出:\n{}", step + 1, llmOutput);
            conversation.append("\n").append(llmOutput);

            // 2b. 检查是否包含 Final Answer
            Matcher finalAnswerMatcher = FINAL_ANSWER_PATTERN.matcher(llmOutput);
            if (finalAnswerMatcher.find()) {
                String finalAnswer = finalAnswerMatcher.group(1).trim();
                log.info("ReAct 完成，共 {} 步，耗时 {}ms",
                        step + 1,
                        Duration.between(startTime, Instant.now()).toMillis());
                return finalAnswer;
            }

            // 2c. 提取 Action 和 Action Input
            Matcher actionMatcher = ACTION_PATTERN.matcher(llmOutput);
            if (!actionMatcher.find()) {
                // 没有找到 Action，也没有 Final Answer，说明 LLM 输出格式异常
                log.warn("ReAct Step {} 未找到 Action 或 Final Answer，尝试退化为直接回答", step + 1);
                // 提取 Thought 作为回答
                Matcher thoughtMatcher = THOUGHT_PATTERN.matcher(llmOutput);
                if (thoughtMatcher.find()) {
                    return thoughtMatcher.group(1).trim();
                }
                return llmOutput.trim();
            }

            String actionName = actionMatcher.group(1).trim();
            String actionInput = actionMatcher.group(2).trim();

            log.info("ReAct Step {}: Action={}, Input={}", step + 1, actionName, actionInput);

            // 2d. 执行工具
            String observation;
            try {
                observation = executeTool(actionName, actionInput);
            } catch (Exception e) {
                log.error("ReAct Step {} 工具执行失败: {}", step + 1, e.getMessage());
                observation = "工具执行错误: " + e.getMessage();
            }

            log.info("ReAct Step {}: Observation=\n{}", step + 1, observation);

            // 2e. 死循环检测：相同的 Observation 出现多次
            observations.add(observation);
            if (isRepeating(observations)) {
                log.warn("ReAct 检测到重复观察，强制终止，已执行 {} 步", step + 1);
                return "我尝试了多次仍然无法完成这个任务，建议您换个方式提问。最后获取到的信息：\n" + observation;
            }

            // 2f. 将 Observation 追加到对话
            conversation.append("\nObservation: ").append(observation).append("\n");

            // 记录 Thought 用于调试
            Matcher thoughtMatcher = THOUGHT_PATTERN.matcher(llmOutput);
            if (thoughtMatcher.find()) {
                thoughtHistory.add(thoughtMatcher.group(1).trim());
            }
        }

        // 3. 达到最大步数，返回已获取的信息
        log.warn("ReAct 达到最大步数 {}，强制终止", MAX_STEPS);
        return "我已经尝试了多种方法，但无法在限制步数内完成您的请求。以下是已获取的信息：\n"
                + (observations.isEmpty() ? "暂无有效信息" : observations.get(observations.size() - 1));
    }

    /**
     * 执行工具调用
     */
    private String executeTool(String actionName, String actionInput) {
        // 清理 actionInput（去除可能的引号包裹）
        actionInput = actionInput.trim();
        if (actionInput.startsWith("\"") && actionInput.endsWith("\"")) {
            actionInput = actionInput.substring(1, actionInput.length() - 1);
        }

        switch (actionName) {
            case "query_database":
                return toolService.queryDatabase(actionInput);
            case "call_external_api":
                // 解析 JSON 格式的输入: {"url": "...", "method": "...", "body": "..."}
                try {
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    Map<String, String> params = mapper.readValue(actionInput, Map.class);
                    return toolService.callExternalApi(
                            params.getOrDefault("url", ""),
                            params.getOrDefault("method", "GET"),
                            params.getOrDefault("body", "")
                    );
                } catch (Exception e) {
                    return "参数解析失败，请使用 JSON 格式: {\"url\": \"...\", \"method\": \"...\", \"body\": \"...\"}";
                }
            default:
                return String.format("未知工具: %s。可用工具: query_database, call_external_api", actionName);
        }
    }

    /**
     * 构建工具描述
     */
    private String buildToolsDescription() {
        return """
                - query_database: 执行 SQL 查询数据库，输入为 SQL 语句字符串
                - call_external_api: 调用外部 HTTP API，输入为 JSON 格式: {"url": "https://...", "method": "GET", "body": ""}
                """;
    }

    /**
     * 构建用户 prompt
     */
    private String buildUserPrompt(String question, String context, String history) {
        StringBuilder sb = new StringBuilder();

        if (!history.isEmpty()) {
            sb.append("对话历史：\n").append(history).append("\n\n");
        }

        if (!context.isEmpty()) {
            sb.append("相关信息：\n").append(context).append("\n\n");
        }

        sb.append("用户问题：").append(question).append("\n\n");
        sb.append("请开始你的思考和行动：\n");
        sb.append("Thought: 我需要分析这个问题并决定如何解决。\n");

        return sb.toString();
    }

    /**
     * 检测是否陷入死循环（相同的 observation 出现超过阈值次数）
     */
    private boolean isRepeating(List<String> observations) {
        if (observations.size() < MAX_REPEATED_OBSERVATIONS) {
            return false;
        }
        String last = observations.get(observations.size() - 1);
        int count = 0;
        for (int i = observations.size() - 1; i >= 0; i--) {
            if (observations.get(i).equals(last)) {
                count++;
            } else {
                break;
            }
        }
        return count >= MAX_REPEATED_OBSERVATIONS;
    }
}