package com.aiagent.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class PlatformMetricsService {

    private static final double[] DEFAULT_PERCENTILES = {0.5, 0.95, 0.99};
    private static final Duration[] CHAT_SLOS = {
            Duration.ofMillis(100),
            Duration.ofMillis(500),
            Duration.ofSeconds(2)
    };

    private final MeterRegistry meterRegistry;

    public Timer.Sample startSample() {
        return Timer.start(meterRegistry);
    }

    public void recordChat(String mode, boolean useRag, boolean cacheHit, boolean success, Timer.Sample sample) {
        String[] tags = {
                "mode", mode,
                "rag", Boolean.toString(useRag),
                "cache", hitTag(cacheHit),
                "status", statusTag(success)
        };
        incrementCounter("ai.chat.requests.total", tags);
        stopSample(sample, "ai.chat.latency", CHAT_SLOS, tags);
    }

    public void recordRagSearch(boolean cacheHit, int resultCount, Timer.Sample sample) {
        String[] tags = {"cache", hitTag(cacheHit)};
        incrementCounter("ai.rag.search.total", tags);
        recordSummary("ai.rag.results.count", resultCount);
        stopSample(sample, "ai.rag.search.latency", new Duration[0], tags);
    }

    public void recordDocumentQueued() {
        incrementCounter("ai.document.ingestion.queued.total");
    }

    public void recordDocumentIngestion(String status, int chunkCount, Timer.Sample sample) {
        String[] tags = {"status", status};
        incrementCounter("ai.document.ingestion.total", tags);
        recordSummary("ai.document.chunk.count", chunkCount);
        stopSample(sample, "ai.document.ingestion.latency", new Duration[0], tags);
    }

    public void recordSemanticCache(boolean hit, double bestSimilarity, Timer.Sample sample) {
        String[] tags = {"result", hitTag(hit)};
        incrementCounter("ai.cache.semantic.total", tags);
        recordSummary("ai.cache.semantic.similarity", (int) (bestSimilarity * 100));
        stopSample(sample, "ai.cache.semantic.latency", new Duration[0], tags);
    }

    public void recordRagCache(boolean hit) {
        incrementCounter("ai.cache.rag.total", "result", hitTag(hit));
    }

    public void recordCacheOperation(String cacheName, String operation, boolean hit) {
        incrementCounter("ai.cache.operations.total",
                "cache", cacheName,
                "operation", operation,
                "result", hitTag(hit));
    }

    public void recordDatabaseQuery(String table, String operation, long durationMs) {
        timerBuilder("ai.database.query.latency", "table", table, "operation", operation)
                .register(meterRegistry)
                .record(Duration.ofMillis(durationMs));
    }

    public void recordAdaptiveRag(String routeType,
                                  String verificationLevel,
                                  boolean rewritten,
                                  int retrievalRounds,
                                  int chunkCount,
                                  String endReason,
                                  boolean success,
                                  Timer.Sample sample) {
        String[] tags = {
                "route", routeType,
                "verification", verificationLevel,
                "rewritten", Boolean.toString(rewritten),
                "end_reason", endReason == null ? "unknown" : endReason,
                "status", statusTag(success)
        };
        incrementCounter("ai.rag.adaptive.total", tags);
        recordSummary("ai.rag.adaptive.rounds", retrievalRounds);
        recordSummary("ai.rag.adaptive.chunk.count", chunkCount);
        stopSample(sample, "ai.rag.adaptive.latency", new Duration[0], tags);
    }

    public void recordReActTrace(String stopReason,
                                 int stepCount,
                                 boolean toolUsed,
                                 boolean toolError,
                                 boolean success,
                                 Timer.Sample sample) {
        String[] tags = {
                "stop_reason", normalizeTag(stopReason),
                "tool_used", Boolean.toString(toolUsed),
                "tool_error", Boolean.toString(toolError),
                "status", statusTag(success)
        };
        incrementCounter("ai.agent.react.total", tags);
        recordSummary("ai.agent.react.steps", stepCount);
        stopSample(sample, "ai.agent.react.latency", new Duration[0], tags);
    }

    public void recordMultiAgentTrace(String stopReason,
                                      int subtaskCount,
                                      int workerFailureCount,
                                      boolean singleAgentFallback,
                                      boolean synthesisFallback,
                                      boolean success,
                                      Timer.Sample sample) {
        String[] tags = {
                "stop_reason", normalizeTag(stopReason),
                "single_agent_fallback", Boolean.toString(singleAgentFallback),
                "synthesis_fallback", Boolean.toString(synthesisFallback),
                "status", statusTag(success)
        };
        incrementCounter("ai.agent.multi.total", tags);
        recordSummary("ai.agent.multi.subtasks", subtaskCount);
        recordSummary("ai.agent.multi.worker.failures", workerFailureCount);
        stopSample(sample, "ai.agent.multi.latency", new Duration[0], tags);
    }

    private void incrementCounter(String name, String... tags) {
        Counter.builder(name)
                .tags(tags)
                .register(meterRegistry)
                .increment();
    }

    private void recordSummary(String name, int value) {
        DistributionSummary.builder(name)
                .publishPercentiles(DEFAULT_PERCENTILES)
                .register(meterRegistry)
                .record(Math.max(value, 0));
    }

    private void stopSample(Timer.Sample sample, String name, Duration[] slos, String... tags) {
        Timer.Builder builder = timerBuilder(name, tags);
        if (slos.length > 0) {
            builder.serviceLevelObjectives(slos);
        }
        sample.stop(builder.register(meterRegistry));
    }

    private Timer.Builder timerBuilder(String name, String... tags) {
        return Timer.builder(name)
                .tags(tags)
                .publishPercentiles(DEFAULT_PERCENTILES);
    }

    private String hitTag(boolean hit) {
        return hit ? "hit" : "miss";
    }

    private String statusTag(boolean success) {
        return success ? "success" : "error";
    }

    private String normalizeTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}