package com.aiagent.infrastructure.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class PersistentIdempotencyService {

    private static final Duration RECORD_TTL = Duration.ofHours(24);

    private final PersistentIdempotencyRepository repository;
    private final ObjectMapper objectMapper;

    @Transactional
    public <T> Optional<T> findCompleted(Long userId,
                                         String expectedSessionId,
                                         PersistentIdempotencyContext context,
                                         Class<T> responseType) {
        if (context == null || !context.enabled()) {
            return Optional.empty();
        }

        Optional<PersistentIdempotencyRecord> existing = repository.findByUserIdAndOperationAndKeyHash(
                userId, context.operation(), context.keyHash());
        if (existing.isEmpty()) {
            return Optional.empty();
        }

        PersistentIdempotencyRecord record = existing.get();
        if (record.getExpiresAt().isBefore(LocalDateTime.now())) {
            repository.delete(record);
            repository.flush();
            return Optional.empty();
        }
        if (!Objects.equals(record.getRequestHash(), context.requestHash())) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key has already been used with a different request payload");
        }
        if (expectedSessionId != null && !Objects.equals(record.getSessionId(), expectedSessionId)) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key has already been used for a different chat session");
        }
        return Optional.of(deserialize(record.getResponsePayload(), responseType));
    }

    @Transactional
    public void saveCompleted(Long userId,
                              String sessionId,
                              PersistentIdempotencyContext context,
                              Object response) {
        if (context == null || !context.enabled()) {
            return;
        }
        repository.save(PersistentIdempotencyRecord.builder()
                .userId(userId)
                .operation(context.operation())
                .keyHash(context.keyHash())
                .requestHash(context.requestHash())
                .sessionId(sessionId)
                .responsePayload(serialize(response))
                .expiresAt(LocalDateTime.now().plus(RECORD_TTL))
                .build());
    }

    @Scheduled(fixedDelayString = "${ai.idempotency.persistent-cleanup-ms:3600000}")
    @Transactional
    public void cleanupExpiredRecords() {
        long deleted = repository.deleteByExpiresAtBefore(LocalDateTime.now());
        if (deleted > 0) {
            log.info("Cleaned {} expired persistent idempotency records", deleted);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize persistent idempotency response", exception);
        }
    }

    private <T> T deserialize(String value, Class<T> responseType) {
        try {
            return objectMapper.readValue(value, responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize persistent idempotency response", exception);
        }
    }
}
