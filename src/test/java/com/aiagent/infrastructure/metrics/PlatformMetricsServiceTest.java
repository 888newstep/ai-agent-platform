package com.aiagent.infrastructure.metrics;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformMetricsServiceTest {

    @Test
    void shouldRecordChatMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlatformMetricsService service = new PlatformMetricsService(registry);
        Timer.Sample sample = service.startSample();

        service.recordChat("react", true, true, true, sample);

        assertThat(registry.get("ai.chat.requests.total")
                .tags("mode", "react", "rag", "true", "cache", "hit", "status", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ai.chat.latency")
                .tags("mode", "react", "rag", "true", "cache", "hit", "status", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void shouldRecordRagSearchMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlatformMetricsService service = new PlatformMetricsService(registry);
        Timer.Sample sample = service.startSample();

        service.recordRagSearch(false, 4, sample);

        assertThat(registry.get("ai.rag.search.total")
                .tags("cache", "miss")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ai.rag.results.count").summary().totalAmount()).isEqualTo(4.0);
        assertThat(registry.get("ai.rag.search.latency")
                .tags("cache", "miss")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void shouldRecordDocumentMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PlatformMetricsService service = new PlatformMetricsService(registry);

        service.recordDocumentQueued();
        Timer.Sample sample = service.startSample();
        service.recordDocumentIngestion("completed", -1, sample);

        assertThat(registry.get("ai.document.ingestion.queued.total").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ai.document.ingestion.total")
                .tags("status", "completed")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("ai.document.chunk.count").summary().totalAmount()).isZero();
        assertThat(registry.get("ai.document.ingestion.latency")
                .tags("status", "completed")
                .timer().count()).isEqualTo(1);
    }
}
