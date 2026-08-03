package com.aiagent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformMetricsService {

    private final MeterRegistry meterRegistry;

    public Timer.Sample startSample() {
        return Timer.start(meterRegistry);
    }

    public void recordChat(String mode, boolean useRag, boolean cacheHit, boolean success, Timer.Sample sample) {
        Counter.builder("ai.chat.requests.total")
                .tag("mode", mode)
                .tag("rag", Boolean.toString(useRag))
                .tag("cache", cacheHit ? "hit" : "miss")
                .tag("status", success ? "success" : "error")
                .register(meterRegistry)
                .increment();

        sample.stop(Timer.builder("ai.chat.latency")
                .tag("mode", mode)
                .tag("rag", Boolean.toString(useRag))
                .tag("cache", cacheHit ? "hit" : "miss")
                .tag("status", success ? "success" : "error")
                .publishPercentiles(0.5, 0.95, 0.99)
                .serviceLevelObjectives(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(2))
                .register(meterRegistry));
    }

    public void recordRagSearch(boolean cacheHit, int resultCount, Timer.Sample sample) {
        Counter.builder("ai.rag.search.total")
                .tag("cache", cacheHit ? "hit" : "miss")
                .register(meterRegistry)
                .increment();

        DistributionSummary.builder("ai.rag.results.count")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
                .record(resultCount);

        sample.stop(Timer.builder("ai.rag.search.latency")
                .tag("cache", cacheHit ? "hit" : "miss")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry));
    }

    public void recordDocumentQueued() {
        Counter.builder("ai.document.ingestion.queued.total")
                .register(meterRegistry)
                .increment();
    }

    public void recordDocumentIngestion(String status, int chunkCount, Timer.Sample sample) {
        Counter.builder("ai.document.ingestion.total")
                .tag("status", status)
                .register(meterRegistry)
                .increment();

        DistributionSummary.builder("ai.document.chunk.count")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
                .record(Math.max(chunkCount, 0));

        sample.stop(Timer.builder("ai.document.ingestion.latency")
                .tag("status", status)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry));
    }
    
    public void recordCacheOperation(String cacheName, String operation, boolean hit) {
        Counter.builder("ai.cache.operations.total")
                .tag("cache", cacheName)
                .tag("operation", operation)
                .tag("result", hit ? "hit" : "miss")
                .register(meterRegistry)
                .increment();
    }
    
    public void recordDatabaseQuery(String table, String operation, long durationMs) {
        Timer.builder("ai.database.query.latency")
                .tag("table", table)
                .tag("operation", operation)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry)
                .record(java.time.Duration.ofMillis(durationMs));
    }
}
