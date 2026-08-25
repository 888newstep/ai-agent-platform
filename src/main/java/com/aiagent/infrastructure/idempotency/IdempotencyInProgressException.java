package com.aiagent.infrastructure.idempotency;

public class IdempotencyInProgressException extends RuntimeException {

    public IdempotencyInProgressException(String message) {
        super(message);
    }
}
