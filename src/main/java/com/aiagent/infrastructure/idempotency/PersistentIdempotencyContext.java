package com.aiagent.infrastructure.idempotency;

import org.springframework.util.StringUtils;

public record PersistentIdempotencyContext(
        String operation,
        String keyHash,
        String requestHash
) {

    public static PersistentIdempotencyContext disabled() {
        return new PersistentIdempotencyContext(null, null, null);
    }

    public boolean enabled() {
        return StringUtils.hasText(operation)
                && StringUtils.hasText(keyHash)
                && StringUtils.hasText(requestHash);
    }
}
