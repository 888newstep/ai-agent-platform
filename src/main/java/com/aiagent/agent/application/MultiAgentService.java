package com.aiagent.agent.application;

import com.aiagent.agent.infrastructure.tool.ToolService;
import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import dev.langchain4j.model.chat.ChatLanguageModel;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultiAgentService {

    private static final Duration WORKER_TIMEOUT = Duration.ofMinutes(2);
    private static final int MAX_SUBTASKS = 5;
    private static final Pattern SUBTASK_PATTERN = Pattern.compile("SUBTASK\\s+(\\d+):\\s*(.+)", Pattern.MULTILINE);

    private static final String PLANNER_PROMPT = """
            TASK PLANNER
            Break the following task into at most %d independent subtasks.

            Requirements:
            - Each subtask should be clear and executable.
            - Subtasks should be parallelizable when possible.
            - Output format must be:
              SUBTASK 1: ...
              SUBTASK 2: ...

            Task: %s
            """;

    private static final String SYNTHESIZER_PROMPT = """
            RESULT SYNTHESIZER
            Merge the following subtask results into one final answer.

            Original task: %s

            Subtask results:
            %s
            """;

    private final ChatLanguageModel chatLanguageModel;
    private final ToolService toolService;
    private final ReActAgent reActAgent;
    private final PlatformMetricsService metricsService;

    public String execute(String task, String context) {
        Instant startTime = Instant.now();
        log.debug("Multi-Agent start task: {}", task);
        try {
            List<String> subtasks = plan(task);
            if (subtasks.isEmpty()) {
                log.warn("Planner returned no subtasks, fallback to single agent");
                return fallbackToSingleAgent(task, context);
            }

            List<String> results = executeInParallel(task, subtasks);
            String finalAnswer = synthesize(task, subtasks, results);
            log.debug("Multi-Agent completed in {} ms with {} workers",
                    Duration.between(startTime, Instant.now()).toMillis(), results.size());
            return finalAnswer;
        } catch (Exception e) {
            log.error("Multi-Agent execution failed", e);
            return fallbackToSingleAgent(task, context);
        }
    }

    public MultiAgentExecutionResult executeDetailed(String task, String context) {
        Timer.Sample sample = metricsService.startSample();
        Instant startTime = Instant.now();
        try {
            List<String> subtasks = plan(task);
            if (subtasks.isEmpty()) {
                String answer = fallbackToSingleAgent(task, context);
                MultiAgentExecutionResult result = buildDetailedResult(task, List.of(), List.of(), startTime,
                        true, false, "single_agent_fallback", answer);
                recordTraceMetrics(result, sample);
                return result;
            }

            List<MultiAgentExecutionTrace.WorkerTrace> workers = executeInParallelDetailed(task, subtasks);
            List<String> results = workers.stream()
                    .sorted(Comparator.comparingInt(w -> w.getIndex()))
                    .map(w -> w.getResult())
                    .toList();
            SynthesisResult synthesisResult = synthesizeDetailed(task, subtasks, results);
            MultiAgentExecutionResult result = buildDetailedResult(
                    task,
                    subtasks,
                    workers,
                    startTime,
                    false,
                    synthesisResult.fallback(),
                    determineDetailedStopReason(workers, synthesisResult.fallback()),
                    synthesisResult.answer()
            );
            recordTraceMetrics(result, sample);
            return result;
        } catch (Exception e) {
            log.error("Multi-Agent detailed execution failed", e);
            String answer = fallbackToSingleAgent(task, context);
            MultiAgentExecutionResult result = buildDetailedResult(task, List.of(), List.of(), startTime,
                    true, false, "error_fallback", answer);
            recordTraceMetrics(result, sample);
            return result;
        }
    }

    private void recordTraceMetrics(MultiAgentExecutionResult result, Timer.Sample sample) {
        MultiAgentExecutionTrace trace = result.getTrace();
        int workerFailureCount = trace == null || trace.getWorkers() == null
                ? 0
                : (int) trace.getWorkers().stream().filter(worker -> !"success".equals(worker.getStatus())).count();
        boolean success = trace != null && ("completed".equals(trace.getStopReason()) || trace.isSingleAgentFallback());
        metricsService.recordMultiAgentTrace(
                trace == null ? "unknown" : trace.getStopReason(),
                trace == null ? 0 : trace.getSubtaskCount(),
                workerFailureCount,
                trace != null && trace.isSingleAgentFallback(),
                trace != null && trace.isSynthesisFallback(),
                success,
                sample
        );
    }

    private List<String> plan(String task) {
        try {
            String prompt = String.format(PLANNER_PROMPT, MAX_SUBTASKS, task);
            String response = chatLanguageModel.generate(prompt);
            List<String> subtasks = new ArrayList<>();
            Matcher matcher = SUBTASK_PATTERN.matcher(response);
            while (matcher.find()) {
                String subtask = matcher.group(2).trim();
                if (!subtask.isEmpty()) {
                    subtasks.add(subtask);
                }
            }
            if (subtasks.isEmpty() && response != null && !response.isBlank() && task != null && !task.isBlank()) {
                subtasks.add(task);
            }
            return subtasks.stream().limit(MAX_SUBTASKS).toList();
        } catch (Exception e) {
            log.error("Task planning failed", e);
            return task == null || task.isBlank() ? List.of() : List.of(task);
        }
    }

    private List<String> executeInParallel(String originalTask, List<String> subtasks) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(subtasks.size(), MAX_SUBTASKS));
        try {
            List<CompletableFuture<WorkerResult>> futures = new ArrayList<>();
            for (int i = 0; i < subtasks.size(); i++) {
                final int index = i;
                final String subtask = subtasks.get(i);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    String result = executeWorker(originalTask, subtask);
                    return new WorkerResult(index, subtask, result);
                }, executor));
            }

            CompletableFuture<Void> allDone = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            try {
                allDone.get(WORKER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("Some workers timed out");
            } catch (Exception e) {
                log.error("Parallel worker execution failed", e);
            }

            List<String> results = new ArrayList<>(Collections.nCopies(subtasks.size(), ""));
            for (CompletableFuture<WorkerResult> future : futures) {
                if (future.isDone() && !future.isCompletedExceptionally()) {
                    WorkerResult worker = future.getNow(null);
                    if (worker != null) {
                        results.set(worker.index(), worker.result());
                    }
                }
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }

    private List<MultiAgentExecutionTrace.WorkerTrace> executeInParallelDetailed(String originalTask, List<String> subtasks) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(subtasks.size(), MAX_SUBTASKS));
        try {
            List<CompletableFuture<MultiAgentExecutionTrace.WorkerTrace>> futures = new ArrayList<>();
            for (int i = 0; i < subtasks.size(); i++) {
                final int index = i;
                final String subtask = subtasks.get(i);
                futures.add(CompletableFuture.supplyAsync(() -> executeWorkerDetailed(index, originalTask, subtask), executor));
            }

            CompletableFuture<Void> allDone = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
            try {
                allDone.get(WORKER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("Detailed worker execution timed out");
            } catch (Exception e) {
                log.error("Detailed parallel worker execution failed", e);
            }

            List<MultiAgentExecutionTrace.WorkerTrace> workers = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                CompletableFuture<MultiAgentExecutionTrace.WorkerTrace> future = futures.get(i);
                if (future.isDone() && !future.isCompletedExceptionally()) {
                    MultiAgentExecutionTrace.WorkerTrace worker = future.getNow(null);
                    if (worker != null) {
                        workers.add(worker);
                        continue;
                    }
                }
                workers.add(MultiAgentExecutionTrace.WorkerTrace.builder()
                        .index(i)
                        .subtask(subtasks.get(i))
                        .result("执行超时")
                        .status("timeout")
                        .latencyMs(WORKER_TIMEOUT.toMillis())
                        .reactTrace(null)
                        .build());
            }
            workers.sort(Comparator.comparingInt(w -> w.getIndex()));
            return workers;
        } finally {
            executor.shutdown();
        }
    }

    private String executeWorker(String originalTask, String subtask) {
        try {
            if (toolService == null) {
                String prompt = String.format(
                        "You are a subtask executor.\n\nOriginal task: %s\n\nSubtask: %s",
                        originalTask, subtask);
                return chatLanguageModel.generate(prompt);
            }
            return reActAgent.execute(subtask, "", "");
        } catch (Exception e) {
            log.error("Worker execution failed: {}", subtask, e);
            return "执行失败: " + e.getMessage();
        }
    }

    private MultiAgentExecutionTrace.WorkerTrace executeWorkerDetailed(int index, String originalTask, String subtask) {
        Instant workerStart = Instant.now();
        try {
            if (toolService == null) {
                String prompt = String.format(
                        "You are a subtask executor.\n\nOriginal task: %s\n\nSubtask: %s",
                        originalTask, subtask);
                String result = chatLanguageModel.generate(prompt);
                return MultiAgentExecutionTrace.WorkerTrace.builder()
                        .index(index)
                        .subtask(subtask)
                        .result(result)
                        .status("success")
                        .latencyMs(Duration.between(workerStart, Instant.now()).toMillis())
                        .reactTrace(null)
                        .build();
            }
            ReActExecutionResult reactResult = reActAgent.executeDetailed(subtask, "", "");
            return MultiAgentExecutionTrace.WorkerTrace.builder()
                    .index(index)
                    .subtask(subtask)
                    .result(reactResult.getAnswer())
                    .status(reactResult.getTrace() != null && reactResult.getTrace().isCompleted() ? "success" : "degraded")
                    .latencyMs(Duration.between(workerStart, Instant.now()).toMillis())
                    .reactTrace(reactResult.getTrace())
                    .build();
        } catch (Exception e) {
            log.error("Detailed worker execution failed: {}", subtask, e);
            return MultiAgentExecutionTrace.WorkerTrace.builder()
                    .index(index)
                    .subtask(subtask)
                    .result("执行失败: " + e.getMessage())
                    .status("error")
                    .latencyMs(Duration.between(workerStart, Instant.now()).toMillis())
                    .reactTrace(null)
                    .build();
        }
    }

    private String synthesize(String task, List<String> subtasks, List<String> results) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < subtasks.size(); i++) {
                sb.append("[Subtask ").append(i + 1).append("] ").append(subtasks.get(i)).append("\n");
                sb.append("[Result ").append(i + 1).append("] ").append(results.get(i)).append("\n\n");
            }
            String prompt = String.format(SYNTHESIZER_PROMPT, task, sb.toString());
            return chatLanguageModel.generate(prompt);
        } catch (Exception e) {
            log.error("Result synthesis failed", e);
            StringBuilder fallback = new StringBuilder();
            fallback.append("以下是各子任务的结果：\n\n");
            for (int i = 0; i < results.size(); i++) {
                fallback.append("[Result ").append(i + 1).append("]\n").append(results.get(i)).append("\n\n");
            }
            return fallback.toString();
        }
    }

    private SynthesisResult synthesizeDetailed(String task, List<String> subtasks, List<String> results) {
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < subtasks.size(); i++) {
                sb.append("[Subtask ").append(i + 1).append("] ").append(subtasks.get(i)).append("\n");
                sb.append("[Result ").append(i + 1).append("] ").append(results.get(i)).append("\n\n");
            }
            String prompt = String.format(SYNTHESIZER_PROMPT, task, sb.toString());
            return new SynthesisResult(chatLanguageModel.generate(prompt), false);
        } catch (Exception e) {
            log.error("Detailed result synthesis failed", e);
            StringBuilder fallback = new StringBuilder();
            fallback.append("以下是各子任务的结果：\n\n");
            for (int i = 0; i < results.size(); i++) {
                fallback.append("[Result ").append(i + 1).append("]\n").append(results.get(i)).append("\n\n");
            }
            return new SynthesisResult(fallback.toString(), true);
        }
    }

    private String determineDetailedStopReason(List<MultiAgentExecutionTrace.WorkerTrace> workers, boolean synthesisFallback) {
        if (synthesisFallback) {
            return "synthesis_fallback";
        }
        boolean hasFailure = workers.stream().anyMatch(worker -> !"success".equals(worker.getStatus()));
        return hasFailure ? "partial_worker_failure" : "completed";
    }

    private MultiAgentExecutionResult buildDetailedResult(String task,
                                                          List<String> subtasks,
                                                          List<MultiAgentExecutionTrace.WorkerTrace> workers,
                                                          Instant startTime,
                                                          boolean singleAgentFallback,
                                                          boolean synthesisFallback,
                                                          String stopReason,
                                                          String answer) {
        return MultiAgentExecutionResult.builder()
                .answer(answer)
                .trace(MultiAgentExecutionTrace.builder()
                        .task(task)
                        .subtaskCount(subtasks.size())
                        .singleAgentFallback(singleAgentFallback)
                        .synthesisFallback(synthesisFallback)
                        .stopReason(stopReason)
                        .totalLatencyMs(Duration.between(startTime, Instant.now()).toMillis())
                        .subtasks(List.copyOf(subtasks))
                        .workers(List.copyOf(workers))
                        .build())
                .build();
    }

    private String fallbackToSingleAgent(String task, String context) {
        String prompt = context == null || context.isEmpty()
                ? String.format("请回答以下问题：\n%s", task)
                : String.format("参考信息：\n%s\n\n问题：%s", context, task);
        return chatLanguageModel.generate(prompt);
    }

    record WorkerResult(int index, String subtask, String result) {}

    private record SynthesisResult(String answer, boolean fallback) {}
}