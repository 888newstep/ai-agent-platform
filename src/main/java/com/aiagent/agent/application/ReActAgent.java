package com.aiagent.agent.application;

import com.aiagent.agent.infrastructure.tool.ToolService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReActAgent {

    private static final int MAX_STEPS = 10;
    private static final Duration TIMEOUT = Duration.ofMinutes(3);
    private static final int MAX_REPEATED_OBSERVATIONS = 3;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String SYSTEM_PROMPT = """
            你是一个智能 AI 助手，可以通过思考和调用工具来解决问题。

            可用工具：
            %s

            你必须严格输出一个 JSON 对象，不要输出 Markdown 代码块，不要输出额外解释。
            JSON 结构如下：
            {
              "thought": "你的思考过程",
              "action": "query_database 或 call_external_api，若直接回答则为 null",
              "actionInput": "工具输入；如果是 call_external_api，也可以是 {\"url\":\"...\",\"method\":\"GET\",\"body\":\"\"}",
              "finalAnswer": "如果已有足够信息，直接给最终答案；否则为 null"
            }

            规则：
            1. 每次只能调用一个工具。
            2. 如果已经得到足够信息，直接返回 finalAnswer。
            3. 如果需要调用工具，finalAnswer 必须为 null。
            4. 始终使用中文。
            """;

    private static final Pattern LEGACY_ACTION_PATTERN = Pattern.compile(
            "Action:\\s*(\\w+)\\s*Action\\s+Input:\\s*(.+?)(?=Thought:|Observation:|Final Answer:|$)",
            Pattern.DOTALL
    );
    private static final Pattern LEGACY_FINAL_ANSWER_PATTERN = Pattern.compile("Final Answer:\\s*(.+)", Pattern.DOTALL);
    private static final Pattern LEGACY_THOUGHT_PATTERN = Pattern.compile("Thought:\\s*(.+?)(?=Action:|Final Answer:|$)", Pattern.DOTALL);
    private static final Pattern HTTP_STATUS_PATTERN = Pattern.compile("^Status:\\s*(\\d{3})");

    private final ChatLanguageModel chatLanguageModel;
    private final ToolService toolService;

    public String execute(String question, String context) {
        return execute(question, context, "");
    }

    public String execute(String question, String context, String history) {
        Instant startTime = Instant.now();
        List<String> observations = new ArrayList<>();

        String toolsDescription = buildToolsDescription();
        String userPrompt = buildUserPrompt(question, context, history);
        StringBuilder conversation = new StringBuilder(userPrompt);

        for (int step = 0; step < MAX_STEPS; step++) {
            if (Duration.between(startTime, Instant.now()).compareTo(TIMEOUT) > 0) {
                log.warn("ReAct Agent timed out after {} steps", step);
                return "抱歉，处理超时了，请简化问题后重试。";
            }

            String fullPrompt = String.format(
                    "%s\n\n%s\n\n%s",
                    SYSTEM_PROMPT.formatted(toolsDescription),
                    conversation,
                    "请继续推理。如果已有足够信息，请直接返回 finalAnswer。"
            );

            String llmOutput;
            try {
                llmOutput = chatLanguageModel.generate(fullPrompt);
            } catch (Exception e) {
                log.error("ReAct model call failed at step {}: {}", step + 1, e.getMessage());
                return "抱歉，AI 模型调用失败，请稍后重试。错误：" + e.getMessage();
            }

            ReActStepResult stepResult = parseStepResult(llmOutput);
            if (stepResult.getFinalAnswer() != null && !stepResult.getFinalAnswer().isBlank()) {
                log.debug("ReAct completed in {} steps", step + 1);
                return stepResult.getFinalAnswer().trim();
            }

            if (stepResult.getAction() == null || stepResult.getAction().isBlank()) {
                log.warn("ReAct step {} returned no action and no final answer", step + 1);
                if (stepResult.getThought() != null && !stepResult.getThought().isBlank()) {
                    return stepResult.getThought().trim();
                }
                return llmOutput == null ? "" : llmOutput.trim();
            }

            String observation;
            try {
                observation = executeTool(stepResult.getAction().trim(), stepResult.getActionInput());
            } catch (Exception e) {
                log.error("ReAct tool execution failed at step {}: {}", step + 1, e.getMessage());
                observation = "工具执行错误: " + e.getMessage();
            }

            observations.add(observation);
            if (isRepeating(observations)) {
                log.warn("ReAct terminated due to repeated observations at step {}", step + 1);
                return "我尝试了多次仍无法完成这个任务。最后获取到的信息：\n" + observation;
            }

            conversation.append("\nAssistant JSON: ").append(compactForPrompt(llmOutput));
            conversation.append("\nObservation: ").append(observation).append("\n");
        }

        log.warn("ReAct reached max steps: {}", MAX_STEPS);
        return "我已经尝试了多种方法，但无法在限制步数内完成请求。"
                + (observations.isEmpty() ? "" : "最后获取到的信息：\n" + observations.get(observations.size() - 1));
    }

    public ReActExecutionResult executeDetailed(String question, String context) {
        return executeDetailed(question, context, "");
    }

    public ReActExecutionResult executeDetailed(String question, String context, String history) {
        Instant startTime = Instant.now();
        List<String> observations = new ArrayList<>();
        List<ReActExecutionTrace.StepTrace> steps = new ArrayList<>();

        String toolsDescription = buildToolsDescription();
        String userPrompt = buildUserPrompt(question, context, history);
        StringBuilder conversation = new StringBuilder(userPrompt);

        for (int step = 0; step < MAX_STEPS; step++) {
            Instant stepStart = Instant.now();
            if (Duration.between(startTime, Instant.now()).compareTo(TIMEOUT) > 0) {
                return buildDetailedResult(
                        question,
                        steps,
                        startTime,
                        "timeout",
                        false,
                        "抱歉，处理超时了，请简化问题后重试。"
                );
            }

            String fullPrompt = String.format(
                    "%s\n\n%s\n\n%s",
                    SYSTEM_PROMPT.formatted(toolsDescription),
                    conversation,
                    "请继续推理。如果已有足够信息，请直接返回 finalAnswer。"
            );

            String llmOutput;
            try {
                llmOutput = chatLanguageModel.generate(fullPrompt);
            } catch (Exception e) {
                steps.add(buildStepTrace(step + 1, null, null, null, null, null,
                        Duration.between(stepStart, Instant.now()).toMillis(), "llm_error"));
                return buildDetailedResult(
                        question,
                        steps,
                        startTime,
                        "llm_error",
                        false,
                        "抱歉，AI 模型调用失败，请稍后重试。错误：" + e.getMessage()
                );
            }

            ReActStepResult stepResult = parseStepResult(llmOutput);
            if (stepResult.getFinalAnswer() != null && !stepResult.getFinalAnswer().isBlank()) {
                String finalAnswer = stepResult.getFinalAnswer().trim();
                steps.add(buildStepTrace(
                        step + 1,
                        stepResult.getThought(),
                        stepResult.getAction(),
                        stepResult.getActionInput(),
                        null,
                        finalAnswer,
                        Duration.between(stepStart, Instant.now()).toMillis(),
                        "not_used"
                ));
                return buildDetailedResult(question, steps, startTime, "final_answer", true, finalAnswer);
            }

            if (stepResult.getAction() == null || stepResult.getAction().isBlank()) {
                String fallbackAnswer = stepResult.getThought() != null && !stepResult.getThought().isBlank()
                        ? stepResult.getThought().trim()
                        : llmOutput == null ? "" : llmOutput.trim();
                steps.add(buildStepTrace(
                        step + 1,
                        stepResult.getThought(),
                        stepResult.getAction(),
                        stepResult.getActionInput(),
                        null,
                        null,
                        Duration.between(stepStart, Instant.now()).toMillis(),
                        "not_used"
                ));
                return buildDetailedResult(question, steps, startTime, "no_action", false, fallbackAnswer);
            }

            String actionName = stepResult.getAction().trim();
            String observation;
            String toolStatus;
            try {
                observation = executeTool(actionName, stepResult.getActionInput());
                toolStatus = determineToolStatus(actionName, observation);
            } catch (Exception e) {
                observation = "工具执行错误: " + e.getMessage();
                toolStatus = "tool_error";
            }

            steps.add(buildStepTrace(
                    step + 1,
                    stepResult.getThought(),
                    actionName,
                    stepResult.getActionInput(),
                    observation,
                    null,
                    Duration.between(stepStart, Instant.now()).toMillis(),
                    toolStatus
            ));

            observations.add(observation);
            if (isRepeating(observations)) {
                return buildDetailedResult(
                        question,
                        steps,
                        startTime,
                        "repeated_observation",
                        false,
                        "我尝试了多次仍无法完成这个任务。最后获取到的信息：\n" + observation
                );
            }

            conversation.append("\nAssistant JSON: ").append(compactForPrompt(llmOutput));
            conversation.append("\nObservation: ").append(observation).append("\n");
        }

        String answer = "我已经尝试了多种方法，但无法在限制步数内完成请求。"
                + (observations.isEmpty() ? "" : "最后获取到的信息：\n" + observations.get(observations.size() - 1));
        return buildDetailedResult(question, steps, startTime, "max_steps", false, answer);
    }

    private ReActExecutionResult buildDetailedResult(String question,
                                                     List<ReActExecutionTrace.StepTrace> steps,
                                                     Instant startTime,
                                                     String stopReason,
                                                     boolean completed,
                                                     String answer) {
        return ReActExecutionResult.builder()
                .answer(answer)
                .trace(ReActExecutionTrace.builder()
                        .question(question)
                        .stepCount(steps.size())
                        .totalLatencyMs(Duration.between(startTime, Instant.now()).toMillis())
                        .stopReason(stopReason)
                        .completed(completed)
                        .steps(List.copyOf(steps))
                        .build())
                .build();
    }

    private ReActExecutionTrace.StepTrace buildStepTrace(int step,
                                                         String thought,
                                                         String action,
                                                         Object actionInput,
                                                         String observation,
                                                         String finalAnswer,
                                                         long stepLatencyMs,
                                                         String toolStatus) {
        return ReActExecutionTrace.StepTrace.builder()
                .step(step)
                .thought(thought)
                .action(action)
                .actionInput(stringifyActionInput(actionInput))
                .observation(observation)
                .finalAnswer(finalAnswer)
                .stepLatencyMs(stepLatencyMs)
                .toolStatus(toolStatus)
                .build();
    }

    private String stringifyActionInput(Object actionInput) {
        if (actionInput == null) {
            return null;
        }
        if (actionInput instanceof String text) {
            return text;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(actionInput);
        } catch (JsonProcessingException e) {
            return String.valueOf(actionInput);
        }
    }

    private String determineToolStatus(String actionName, String observation) {
        if (!"query_database".equals(actionName) && !"call_external_api".equals(actionName)) {
            return "unknown_tool";
        }
        if (observation != null
                && (observation.startsWith("Error:") || observation.startsWith("参数解析失败"))) {
            return "tool_error";
        }
        if (observation != null) {
            Matcher statusMatcher = HTTP_STATUS_PATTERN.matcher(observation.trim());
            if (statusMatcher.find() && Integer.parseInt(statusMatcher.group(1)) >= 400) {
                return "tool_error";
            }
        }
        return "success";
    }

    private ReActStepResult parseStepResult(String llmOutput) {
        ReActStepResult structured = parseStructuredJson(llmOutput);
        if (structured != null) {
            return structured;
        }
        return parseLegacyText(llmOutput);
    }

    private ReActStepResult parseStructuredJson(String llmOutput) {
        String normalized = stripMarkdownCodeFence(llmOutput);
        try {
            ReActStepPayload payload = OBJECT_MAPPER.readValue(normalized, ReActStepPayload.class);
            return ReActStepResult.builder()
                    .thought(payload.getThought())
                    .action(payload.getAction())
                    .actionInput(payload.getActionInput())
                    .finalAnswer(payload.getFinalAnswer())
                    .build();
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private ReActStepResult parseLegacyText(String llmOutput) {
        Matcher finalAnswerMatcher = LEGACY_FINAL_ANSWER_PATTERN.matcher(llmOutput);
        if (finalAnswerMatcher.find()) {
            return ReActStepResult.builder()
                    .thought(extractLegacyThought(llmOutput))
                    .finalAnswer(finalAnswerMatcher.group(1).trim())
                    .build();
        }

        Matcher actionMatcher = LEGACY_ACTION_PATTERN.matcher(llmOutput);
        if (actionMatcher.find()) {
            return ReActStepResult.builder()
                    .thought(extractLegacyThought(llmOutput))
                    .action(actionMatcher.group(1).trim())
                    .actionInput(actionMatcher.group(2).trim())
                    .build();
        }

        return ReActStepResult.builder()
                .thought(extractLegacyThought(llmOutput))
                .build();
    }

    private String extractLegacyThought(String llmOutput) {
        Matcher thoughtMatcher = LEGACY_THOUGHT_PATTERN.matcher(llmOutput);
        return thoughtMatcher.find() ? thoughtMatcher.group(1).trim() : null;
    }

    private String executeTool(String actionName, Object actionInput) {
        return switch (actionName) {
            case "query_database" -> toolService.queryDatabase(normalizeQueryInput(actionInput));
            case "call_external_api" -> executeExternalApi(actionInput);
            default -> String.format("未知工具: %s。可用工具: query_database, call_external_api", actionName);
        };
    }

    private String normalizeQueryInput(Object actionInput) {
        if (actionInput == null) {
            return "";
        }
        String input = String.valueOf(actionInput).trim();
        if (input.startsWith("\"") && input.endsWith("\"") && input.length() >= 2) {
            return input.substring(1, input.length() - 1);
        }
        return input;
    }

    private String executeExternalApi(Object actionInput) {
        try {
            Map<String, String> params;
            if (actionInput instanceof Map<?, ?> map) {
                params = OBJECT_MAPPER.convertValue(map, new TypeReference<>() {});
            } else {
                params = OBJECT_MAPPER.readValue(normalizeQueryInput(actionInput), new TypeReference<>() {});
            }
            return toolService.callExternalApi(
                    params.getOrDefault("url", ""),
                    params.getOrDefault("method", "GET"),
                    params.getOrDefault("body", "")
            );
        } catch (Exception e) {
            return "参数解析失败，请使用 JSON 格式: {\"url\":\"...\",\"method\":\"GET\",\"body\":\"\"}";
        }
    }

    private String buildToolsDescription() {
        return """
                - query_database: 执行 SQL 查询数据库，输入为 SQL 语句字符串
                - call_external_api: 调用外部 HTTP API，输入为 JSON 格式: {"url":"https://...","method":"GET","body":""}
                """;
    }

    private String buildUserPrompt(String question, String context, String history) {
        StringBuilder builder = new StringBuilder();
        if (history != null && !history.isBlank()) {
            builder.append("对话历史：\n").append(history).append("\n\n");
        }
        if (context != null && !context.isBlank()) {
            builder.append("相关信息：\n").append(context).append("\n\n");
        }
        builder.append("用户问题：").append(question).append("\n");
        return builder.toString();
    }

    private String stripMarkdownCodeFence(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            if (firstLineBreak >= 0) {
                trimmed = trimmed.substring(firstLineBreak + 1, trimmed.length() - 3).trim();
            }
        }
        return trimmed;
    }

    private String compactForPrompt(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private boolean isRepeating(List<String> observations) {
        if (observations.size() < MAX_REPEATED_OBSERVATIONS) {
            return false;
        }
        String last = observations.get(observations.size() - 1);
        int count = 0;
        for (int index = observations.size() - 1; index >= 0; index--) {
            if (observations.get(index).equals(last)) {
                count++;
            } else {
                break;
            }
        }
        return count >= MAX_REPEATED_OBSERVATIONS;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ReActStepResult {
        private String thought;
        private String action;
        private Object actionInput;
        private String finalAnswer;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ReActStepPayload {
        private String thought;
        private String action;
        private Object actionInput;
        private String finalAnswer;
    }
}
