package com.aiagent.infrastructure.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PersistentIdempotencyRepository extends JpaRepository<PersistentIdempotencyRecord, Long> {

    Optional<PersistentIdempotencyRecord> findByUserIdAndOperationAndKeyHash(
            Long userId, String operation, String keyHash);

    long deleteByExpiresAtBefore(LocalDateTime expiresAt);
}
