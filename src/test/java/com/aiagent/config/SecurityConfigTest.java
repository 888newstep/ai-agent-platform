package com.aiagent.config;

import com.aiagent.agent.AiAgentService;
import com.aiagent.cache.SemanticCacheService;
import com.aiagent.controller.AiAgentController;
import com.aiagent.document.DocumentService;
import com.aiagent.security.JwtAuthenticationFilter;
import com.aiagent.evaluation.RagEvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyList;
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
    private com.aiagent.agent.MultiAgentService multiAgentService;

    @MockitoBean
    private RagEvaluationService ragEvaluationService;

    @MockitoBean
    private AiProperties aiProperties;

    @MockitoBean
    private com.aiagent.security.JwtTokenProvider jwtTokenProvider;

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
    void shouldRejectProtectedEndpointWithoutAdminApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/agent/evaluate"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAllowProtectedEndpointWithValidAdminApiKey() throws Exception {
        RagEvaluationService.EvaluationReport report = new RagEvaluationService.EvaluationReport();
        report.setTopKs(List.of(1, 3, 5));
        report.setDatasetSize(1);
        report.setConfigSnapshot(Map.of());
        when(ragEvaluationService.quickEvaluate(anyList())).thenReturn(report);

        mockMvc.perform(post("/api/v1/agent/evaluate")
                        .header("X-Admin-Api-Key", "test-admin-key"))
                .andExpect(status().isOk());
    }
}
