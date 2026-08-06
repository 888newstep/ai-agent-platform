package com.aiagent;

import com.aiagent.infrastructure.metrics.PlatformMetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ai.vector-store.milvus.host=localhost",
        "ai.vector-store.milvus.port=19530",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.datasource.url=jdbc:h2:mem:testdb-observability",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "ai.model.provider=local",
        "ai.embedding.provider=local",
        "ai.model.local.base-url=http://localhost:11434/v1",
        "ai.model.local.model-name=dummy",
        "ai.model.local.api-key=dummy",
        "ai.model.deepseek.api-key=dummy",
        "ai.model.qianwen.api-key=dummy",
        "ai.model.doubao.api-key=dummy",
        "ai.model.qwen3-flash.api-key=dummy",
        "ai.embedding.local.base-url=http://localhost:11434/v1",
        "ai.embedding.local.model-name=dummy",
        "ai.embedding.local.dimension=1024",
        "ai.embedding.siliconflow.api-key=dummy",
        "jwt.secret=test-jwt-secret-for-ci-only-must-be-long-enough-32-chars"
})
class ObservabilityEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PlatformMetricsService metricsService;

    @Test
    void shouldExposeHealthEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void shouldExposeCustomMetrics() throws Exception {
        metricsService.recordDocumentQueued();

        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names", hasItem("ai.document.ingestion.queued.total")));

        mockMvc.perform(get("/actuator/metrics/ai.document.ingestion.queued.total"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ai.document.ingestion.queued.total")));
    }
}
