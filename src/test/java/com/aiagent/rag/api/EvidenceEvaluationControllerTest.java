package com.aiagent.rag.api;

import com.aiagent.rag.application.EvidenceVerificationEvaluationService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceEvaluationControllerTest {

    @Test
    void shouldDelegateEvidenceEvaluationRequest() {
        EvidenceVerificationEvaluationService service = mock(EvidenceVerificationEvaluationService.class);
        EvidenceVerificationEvaluationService.EvaluationReport report =
                EvidenceVerificationEvaluationService.EvaluationReport.builder()
                        .sampleCount(6)
                        .liveRerank(true)
                        .build();
        when(service.evaluateFromFile("dataset.json", true)).thenReturn(report);

        EvidenceVerificationEvaluationService.EvaluationReport response =
                new EvidenceEvaluationController(service).evaluate("dataset.json", true);

        assertThat(response).isSameAs(report);
        verify(service).evaluateFromFile("dataset.json", true);
    }
}
