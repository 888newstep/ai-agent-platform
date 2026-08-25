package com.aiagent.infrastructure.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "ai:idempotency:";
    private static final Duration RECORD_TTL = Duration.ofHours(24);
    private static final long WAIT_TIMEOUT_MILLIS = 30_000L;
    private static final long WAIT_INTERVAL_MILLIS = 100L;
    private static final DefaultRedisScript<Long> COMPARE_AND_SET_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                redis.call('set', KEYS[1], ARGV[2], 'PX', ARGV[3])
                return 1
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public <T> T execute(String operation,
                          String idempotencyKey,
                          String requestHash,
                          Class<T> responseType,
                          Supplier<T> action) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return action.get();
        }

        IdempotencyClaim claim = claim(operation, idempotencyKey, requestHash);
        if (claim.status() == ClaimStatus.COMPLETED) {
            return readPayload(claim.payload(), responseType);
        }
        if (claim.status() == ClaimStatus.IN_PROGRESS) {
            return awaitCompleted(operation, idempotencyKey, requestHash, responseType);
        }

        try {
            T response = action.get();
            complete(operation, idempotencyKey, requestHash, claim.ownerToken(), response);
            return response;
        } catch (RuntimeException | Error exception) {
            release(operation, idempotencyKey, requestHash, claim.ownerToken());
            throw exception;
        }
    }

    public <V> Map<String, V> executeMap(String operation,
                                         String idempotencyKey,
                                         String requestHash,
                                         Supplier<Map<String, V>> action) {
        return execute(operation, idempotencyKey, requestHash, mapResponseType(), action);
    }

    public IdempotencyClaim claim(String operation, String idempotencyKey, String requestHash) {
        validateKey(idempotencyKey);
        String redisKey = buildRedisKey(operation, idempotencyKey);
        String ownerToken = UUID.randomUUID().toString();
        String processingPayload = serialize(new StoredRecord("PROCESSING", requestHash, ownerToken, null));
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey, processingPayload, RECORD_TTL);
        if (Boolean.TRUE.equals(acquired)) {
            return new IdempotencyClaim(ClaimStatus.ACQUIRED, null, ownerToken);
        }

        String existingPayload = redisTemplate.opsForValue().get(redisKey);
        if (!StringUtils.hasText(existingPayload)) {
            return claim(operation, idempotencyKey, requestHash);
        }

        StoredRecord record = deserialize(existingPayload, StoredRecord.class);
        ensureSameRequest(record, requestHash);
        if ("COMPLETED".equals(record.state())) {
            return new IdempotencyClaim(ClaimStatus.COMPLETED, record.payload(), null);
        }
        return new IdempotencyClaim(ClaimStatus.IN_PROGRESS, null, null);
    }

    public void complete(String operation,
                         String idempotencyKey,
                         String requestHash,
                         String ownerToken,
                         Object response) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return;
        }
        String redisKey = buildRedisKey(operation, idempotencyKey);
        String expectedPayload = serialize(new StoredRecord("PROCESSING", requestHash, ownerToken, null));
        String completedPayload = serialize(
                new StoredRecord("COMPLETED", requestHash, null, serialize(response)));
        redisTemplate.execute(COMPARE_AND_SET_SCRIPT, List.of(redisKey),
                expectedPayload, completedPayload, Long.toString(RECORD_TTL.toMillis()));
    }

    public void release(String operation, String idempotencyKey, String requestHash, String ownerToken) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return;
        }
        String redisKey = buildRedisKey(operation, idempotencyKey);
        String expectedPayload = serialize(new StoredRecord("PROCESSING", requestHash, ownerToken, null));
        redisTemplate.execute(COMPARE_AND_DELETE_SCRIPT, List.of(redisKey), expectedPayload);
    }

    public <T> T awaitCompleted(String operation,
                                 String idempotencyKey,
                                 String requestHash,
                                 Class<T> responseType) {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;
        String redisKey = buildRedisKey(operation, idempotencyKey);
        while (System.currentTimeMillis() < deadline) {
            String existingPayload = redisTemplate.opsForValue().get(redisKey);
            if (!StringUtils.hasText(existingPayload)) {
                throw new IdempotencyInProgressException("The idempotent request lease expired before completion");
            }
            StoredRecord record = deserialize(existingPayload, StoredRecord.class);
            ensureSameRequest(record, requestHash);
            if ("COMPLETED".equals(record.state())) {
                return readPayload(record.payload(), responseType);
            }
            sleep();
        }
        throw new IdempotencyInProgressException("The idempotent request is still processing");
    }

    public <T> T readCompleted(String payload, Class<T> responseType) {
        return readPayload(payload, responseType);
    }

    public <V> Map<String, V> readCompletedMap(String payload) {
        return readPayload(payload, mapResponseType());
    }

    public String fingerprint(String... values) {
        String material = String.join("\u001f", values == null ? new String[0] : values);
        return sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    public String fingerprint(byte[] value) {
        return sha256(value == null ? new byte[0] : value);
    }

    private void sleep() {
        try {
            Thread.sleep(WAIT_INTERVAL_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IdempotencyInProgressException("Interrupted while waiting for completion");
        }
    }

    @SuppressWarnings("unchecked")
    private static <V> Class<Map<String, V>> mapResponseType() {
        return (Class<Map<String, V>>) (Class<?>) Map.class;
    }

    private String buildRedisKey(String operation, String idempotencyKey) {
        return KEY_PREFIX + operation + ":" + sha256(idempotencyKey.getBytes(StandardCharsets.UTF_8));
    }

    private void validateKey(String idempotencyKey) {
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key must contain 1 to 200 characters");
        }
    }

    private void ensureSameRequest(StoredRecord record, String requestHash) {
        if (!Objects.equals(record.requestHash(), requestHash)) {
            throw new IdempotencyConflictException(
                    "Idempotency-Key has already been used with a different request payload");
        }
    }

    private <T> T readPayload(String payload, Class<T> responseType) {
        return deserialize(payload, responseType);
    }

    private <T> T deserialize(String payload, Class<T> responseType) {
        try {
            return objectMapper.readValue(payload, responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize idempotency response", exception);
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize idempotency response", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public enum ClaimStatus {
        ACQUIRED,
        IN_PROGRESS,
        COMPLETED
    }

    public record IdempotencyClaim(ClaimStatus status, String payload, String ownerToken) {
    }

    private record StoredRecord(String state, String requestHash, String ownerToken, String payload) {
    }
}
