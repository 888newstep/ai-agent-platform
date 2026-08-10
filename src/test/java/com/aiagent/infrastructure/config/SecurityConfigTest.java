package com.aiagent.infrastructure.config;

import com.aiagent.agent.api.AiAgentController;
import com.aiagent.agent.application.AiAgentService;
import com.aiagent.infrastructure.cache.SemanticCacheService;
import com.aiagent.infrastructure.security.JwtAuthenticationFilter;
import com.aiagent.knowledge.application.DocumentService;
import com.aiagent.rag.application.AdaptiveRagContext;
import com.aiagent.rag.application.RagEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AiAgentController.class)
@Import({SecurityConfig.class, AdminApiKeyFilter.class, JwtAuthenticationFilter.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AiAgentService aiAgentService;

    @MockitoBean
    private DocumentService documentService;

    @MockitoBean
    private SemanticCacheService semanticCacheService;

    @MockitoBean(name = "multiAgentService")
    private com.aiagent.agent.application.MultiAgentService multiAgentService;

    @MockitoBean
    private RagEvaluationService ragEvaluationService;

    @MockitoBean
    private AiProperties aiProperties;

    @MockitoBean
    private com.aiagent.infrastructure.security.JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        AiProperties.Security security = new AiProperties.Security();
        security.setAdminApiKey("test-admin-key");
        security.setAdminHeaderName("X-Admin-Api-Key");
        when(aiProperties.getSecurity()).thenReturn(security);
    }

    @Test
    void shouldAllowPublicHealthEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/agent/health"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldRejectProtectedEvaluateEndpointWithoutAdminApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/agent/evaluate"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectProtectedCompareEndpointWithoutAdminApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/agent/evaluate/compare")
                        .param("datasetPath", "dataset.json"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectProtectedAdaptiveRagDebugEndpointWithoutAdminApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/agent/rag/debug")
                        .param("question", "refund"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectProtectedEvaluateEndpointWithRegularUserJwt() throws Exception {
        when(jwtTokenProvider.validateToken("user-token")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken("user-token")).thenReturn("user");

        mockMvc.perform(post("/api/v1/agent/evaluate")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden());
    }
    @Test
    void shouldAllowProtectedEvaluateEndpointWithValidAdminApiKey() throws Exception {
        RagEvaluationService.EvaluationReport report = new RagEvaluationService.EvaluationReport();
        report.setDatasetSource("built-in-sample");
        report.setTopKs(List.of(1, 3, 5));
        report.setDatasetSize(1);
        report.setConfigSnapshot(Map.of());
        when(ragEvaluationService.quickEvaluate(anyList(), eq(null), eq(null))).thenReturn(report);

        mockMvc.perform(post("/api/v1/agent/evaluate")
                        .header("X-Admin-Api-Key", "test-admin-key"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldAllowProtectedAdaptiveRagDebugEndpointWithValidAdminApiKey() throws Exception {
        when(aiAgentService.inspectAdaptiveRag("refund", true)).thenReturn(AdaptiveRagContext.empty("refund"));

        mockMvc.perform(post("/api/v1/agent/rag/debug")
                        .header("X-Admin-Api-Key", "test-admin-key")
                        .param("question", "refund"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldAllowProtectedCompareEndpointWithValidAdminApiKey() throws Exception {
        RagEvaluationService.EvaluationReport evaluationReport = new RagEvaluationService.EvaluationReport();
        evaluationReport.setDatasetSource("dataset.json");
        evaluationReport.setTopKs(List.of(1));
        evaluationReport.setDatasetSize(1);
        evaluationReport.setConfigSnapshot(Map.of());

        RagEvaluationService.ComparisonReport report = new RagEvaluationService.ComparisonReport();
        report.setDatasetSource("dataset.json");
        report.setDatasetSize(1);
        report.setTopKs(List.of(1));
        report.setReports(Map.of("baseline", evaluationReport));
        when(ragEvaluationService.compareFromFile(eq("dataset.json"), anyList(), anyList())).thenReturn(report);

        mockMvc.perform(post("/api/v1/agent/evaluate/compare")
                        .header("X-Admin-Api-Key", "test-admin-key")
                        .param("datasetPath", "dataset.json"))
                .andExpect(status().isOk());
    }
}