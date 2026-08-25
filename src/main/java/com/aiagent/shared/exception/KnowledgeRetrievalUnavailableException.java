package com.aiagent.shared.exception;

public class KnowledgeRetrievalUnavailableException extends RuntimeException {

    public KnowledgeRetrievalUnavailableException(String message) {
        super(message);
    }

    public KnowledgeRetrievalUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
