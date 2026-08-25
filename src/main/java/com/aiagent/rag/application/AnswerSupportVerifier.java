package com.aiagent.rag.application;

public interface AnswerSupportVerifier {
    AnswerSupportResult verify(String question, String answer, String evidenceContext);
}
