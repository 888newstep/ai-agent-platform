package com.aiagent.agent.application;

import com.aiagent.agent.infrastructure.tool.ToolService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多智能体协作服务 — Supervisor + Worker 模式
 *
 * <p>核心流程：
 * <ol>
 *   <li><b>规划</b>：Supervisor 将复杂任务拆解为多个子任务</li>
 *   <li><b>执行</b>：多个 Worker 并行执行子任务（各使用独立的 ReAct 循环）</li>
 *   <li><b>汇总</b>：Supervisor 收集所有 Worker 结果，合成最终回答</li>
 * </ol>
 *
 * <p>面试价值（Q147 多智能体协作模式）：
 * <ul>
 *   <li>Supervisor 协调员模式 — 一个 Agent 负责拆解，多个子 Agent 并行执行</li>
 *   <li>模块化设计 — 各 Agent 专注于擅长领域</li>
 *   <li>并行执行 — 利用 CompletableFuture 实现并发</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiAgentService {

    private final ChatLanguageModel chatLanguageModel;
    private final ToolService toolService;

    /** Worker 并行超时时间 */
    private static final Duration WORKER_TIMEOUT = Duration.ofMinutes(2);

    /** 最大子任务数 */
    private static final int MAX_SUBTASKS = 5;

    /** Supervisor 规划提示词 */
    private static final String PLANNER_PROMPT = """
            你是一个任务规划专家。请将以下复杂任务拆解为 %d 个以内的子任务。
            
            要求：
            - 每个子任务应该是独立的、可并行执行的
            - 子任务之间不应有依赖关系
            - 每个子任务描述要清晰、具体
            - 使用中文
            
            输出格式（严格遵守）：
            SUBTASK 1: 子任务1描述
            SUBTASK 2: 子任务2描述
            ...
            
            任务：%s
            """;

    /** Supervisor 汇总提示词 */
    private static final String SYNTHESIZER_PROMPT = """
            你是一个结果汇总专家。请将以下多个子任务的结果整合成一个完整、连贯的最终回答。
            
            原始任务：%s
            
            子任务结果：
            %s
            
            请整合这些结果，输出一个完整的最终回答。注意：
            - 去除重复内容
            - 按逻辑顺序组织
            - 保持信息的完整性和准确性
            - 用中文回答
            """;

    /**
     * 执行多智能体协作任务
     *
     * @param task    复杂任务描述
     * @param context RAG 上下文（可选）
     * @return 最终汇总回答
     */
    public String execute(String task, String context) {
        Instant startTime = Instant.now();
        log.info("Multi-Agent 开始执行任务: {}", task);

        try {
            // 1. 规划阶段：Supervisor 拆解任务
            List<String> subtasks = plan(task);
            log.info("任务拆解为 {} 个子任务", subtasks.size());

            if (subtasks.isEmpty()) {
                log.warn("任务拆解失败，退化为单 Agent 执行");
                return fallbackToSingleAgent(task, context);
            }

            // 2. 执行阶段：Worker 并行执行
            List<String> results = executeInParallel(task, subtasks);

            // 3. 汇总阶段：Supervisor 合成最终回答
            String finalAnswer = synthesize(task, subtasks, results);

            log.info("Multi-Agent 完成，耗时 {}ms，{} 个 Worker",
                    Duration.between(startTime, Instant.now()).toMillis(),
                    results.size());

            return finalAnswer;

        } catch (Exception e) {
            log.error("Multi-Agent 执行失败", e);
            return fallbackToSingleAgent(task, context);
        }
    }

    /**
     * 规划阶段：使用 LLM 将任务拆解为子任务
     */
    private List<String> plan(String task) {
        try {
            String prompt = String.format(PLANNER_PROMPT, MAX_SUBTASKS, task);
            String response = chatLanguageModel.generate(prompt);
            log.debug("Supervisor 规划结果:\n{}", response);

            List<String> subtasks = new ArrayList<>();
            Pattern pattern = Pattern.compile("SUBTASK\\s+(\\d+):\\s*(.+)", Pattern.MULTILINE);
            Matcher matcher = pattern.matcher(response);

            while (matcher.find()) {
                String subtask = matcher.group(2).trim();
                if (!subtask.isEmpty()) {
                    subtasks.add(subtask);
                }
            }

            return subtasks;
        } catch (Exception e) {
            log.error("任务规划失败", e);
            return List.of();
        }
    }

    /**
     * 执行阶段：Worker 并行执行子任务
     */
    private List<String> executeInParallel(String task, List<String> subtasks) {
        // 使用 ForkJoinPool 实现并行执行
        ExecutorService executor = Executors.newWorkStealingPool();
        try {
            List<CompletableFuture<WorkerResult>> futures = new ArrayList<>();

            for (int i = 0; i < subtasks.size(); i++) {
                int index = i;
                String subtask = subtasks.get(i);
                CompletableFuture<WorkerResult> future = CompletableFuture
                        .supplyAsync(() -> {
                            log.info("Worker {} 开始执行: {}", index + 1, subtask);
                            String result = workerExecute(subtask);
                            log.info("Worker {} 完成, 结果长度: {}", index + 1, result.length());
                            return new WorkerResult(index, subtask, result);
                        }, executor)
                        .orTimeout(WORKER_TIMEOUT.getSeconds(), TimeUnit.SECONDS)
                        .exceptionally(e -> {
                            log.warn("Worker {} 执行失败: {}", index + 1, e.getMessage());
                            return new WorkerResult(index, subtask, "[Worker " + (index + 1) + " 执行失败: " + e.getMessage() + "]");
                        });
                futures.add(future);
            }

            // 等待所有 Worker 完成
            return futures.stream()
                    .map(CompletableFuture::join)
                    .sorted(Comparator.comparingInt(WorkerResult::index))
                    .map(WorkerResult::result)
                    .toList();
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Worker 执行单个子任务（使用 ReAct 循环或直接 LLM）
     */
    private String workerExecute(String subtask) {
        try {
            // 检查是否需要工具调用（包含"查询""搜索""计算"等关键词）
            boolean needsTools = subtask.matches(".*(查询|搜索|计算|统计|查找|获取|数据库|API).*");

            if (needsTools) {
                // 需要工具调用时使用 ReAct 模式
                return executeWorkerReAct(subtask);
            } else {
                // 纯推理任务直接使用 LLM
                String prompt = String.format("请完成以下任务：\n%s\n\n请用中文回答。", subtask);
                return chatLanguageModel.generate(prompt);
            }
        } catch (Exception e) {
            log.error("Worker 执行子任务失败: {}", subtask, e);
            return "执行失败: " + e.getMessage();
        }
    }

    /**
     * Worker 的 ReAct 循环（简化版，不含死循环检测）
     */
    private String executeWorkerReAct(String task) {
        String toolsDesc = """
                - query_database: 执行 SQL 查询数据库
                - call_external_api: 调用外部 HTTP API
                """;

        String systemPrompt = String.format("""
                你是一个 Worker Agent，负责完成以下子任务。
                你可以使用工具来获取信息。
                
                可用工具：
                %s
                
                格式：
                Thought: 你的思考
                Action: 工具名
                Action Input: 工具参数
                Observation: 工具结果
                ...（重复）
                Final Answer: 最终回答
                """, toolsDesc);

        StringBuilder conversation = new StringBuilder();
        conversation.append("子任务：").append(task).append("\n\n");

        for (int step = 0; step < 5; step++) {
            String prompt = systemPrompt + "\n\n" + conversation + "\n请继续：";
            String response = chatLanguageModel.generate(prompt);

            Pattern finalAnswerPattern = Pattern.compile("Final Answer:\\s*(.+)", Pattern.DOTALL);
            Matcher finalMatcher = finalAnswerPattern.matcher(response);
            if (finalMatcher.find()) {
                return finalMatcher.group(1).trim();
            }

            Pattern actionPattern = Pattern.compile("Action:\\s*(\\w+)\\s*Action\\s+Input:\\s*(.+?)(?=Thought:|Observation:|Final Answer:|$)", Pattern.DOTALL);
            Matcher actionMatcher = actionPattern.matcher(response);
            if (actionMatcher.find()) {
                String actionName = actionMatcher.group(1).trim();
                String actionInput = actionMatcher.group(2).trim();
                String observation;

                try {
                    observation = switch (actionName) {
                        case "query_database" -> toolService.queryDatabase(actionInput);
                        case "call_external_api" -> {
                            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                            Map<String, String> params = mapper.readValue(actionInput, Map.class);
                            yield toolService.callExternalApi(
                                    params.getOrDefault("url", ""),
                                    params.getOrDefault("method", "GET"),
                                    params.getOrDefault("body", ""));
                        }
                        default -> "未知工具: " + actionName;
                    };
                } catch (Exception e) {
                    observation = "工具执行错误: " + e.getMessage();
                }

                conversation.append("\n").append(response).append("\nObservation: ").append(observation);
            } else {
                // 没有找到 Action 也没有 Final Answer，返回原始响应
                return response;
            }
        }

        return "已尽力完成，但未能完全解决子任务。";
    }

    /**
     * 汇总阶段：Supervisor 合成最终回答
     */
    private String synthesize(String task, List<String> subtasks, List<String> results) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < subtasks.size(); i++) {
                sb.append("【子任务 ").append(i + 1).append("】").append(subtasks.get(i)).append("\n");
                sb.append("【结果 ").append(i + 1).append("】").append(results.get(i)).append("\n\n");
            }

            String prompt = String.format(SYNTHESIZER_PROMPT, task, sb.toString());
            return chatLanguageModel.generate(prompt);
        } catch (Exception e) {
            log.error("结果汇总失败", e);
            // 汇总失败时，直接拼接所有结果
            StringBuilder fallback = new StringBuilder();
            fallback.append("以下是各子任务的结果：\n\n");
            for (int i = 0; i < results.size(); i++) {
                fallback.append("【结果 ").append(i + 1).append("】\n").append(results.get(i)).append("\n\n");
            }
            return fallback.toString();
        }
    }

    /**
     * 退化为单 Agent 执行
     */
    private String fallbackToSingleAgent(String task, String context) {
        String prompt = context.isEmpty()
                ? String.format("请回答以下问题：\n%s", task)
                : String.format("参考信息：\n%s\n\n问题：%s", context, task);
        return chatLanguageModel.generate(prompt);
    }

    /** Worker 执行结果 */
    record WorkerResult(int index, String subtask, String result) {}
}