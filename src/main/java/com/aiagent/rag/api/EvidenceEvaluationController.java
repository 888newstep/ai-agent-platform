package com.aiagent.rag.api;

import com.aiagent.rag.application.EvidenceVerificationEvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/agent/evaluate/evidence")
@RequiredArgsConstructor
public class EvidenceEvaluationController {

    private final EvidenceVerificationEvaluationService evaluationService;

    @PostMapping
    public EvidenceVerificationEvaluationService.EvaluationReport evaluate(
            @RequestParam(defaultValue = "./examples/evaluation-datasets/evidence-verification-sample.json")
            String datasetPath,
            @RequestParam(defaultValue = "false") boolean liveRerank) {
        return evaluationService.evaluateFromFile(datasetPath, liveRerank);
    }
}
