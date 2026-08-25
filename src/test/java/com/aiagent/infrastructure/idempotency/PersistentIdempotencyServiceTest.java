package com.aiagent.infrastructure.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PersistentIdempotencyServiceTest {

    @Mock
    private PersistentIdempotencyRepository repository;

    private PersistentIdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new PersistentIdempotencyService(repository, new ObjectMapper());
    }

    @Test
    void shouldStoreSerializedCompletion() {
        PersistentIdempotencyContext context = context("request-hash");

        service.saveCompleted(7L, "session-1", context, Map.of("answer", "ok"));

        ArgumentCaptor<PersistentIdempotencyRecord> captor =
                ArgumentCaptor.forClass(PersistentIdempotencyRecord.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(7L);
        assertThat(captor.getValue().getSessionId()).isEqualTo("session-1");
        assertThat(captor.getValue().getResponsePayload()).contains("\"answer\":\"ok\"");
        assertThat(captor.getValue().getExpiresAt()).isAfter(LocalDateTime.now());
    }

    @Test
    void shouldReadCompletionAfterRedisLoss() {
        PersistentIdempotencyContext context = context("request-hash");
        when(repository.findByUserIdAndOperationAndKeyHash(7L, "agent-chat", "key-hash"))
                .thenReturn(Optional.of(record("request-hash", "session-1", "\"stored answer\"")));

        Optional<String> result = service.findCompleted(
                7L, "session-1", context, String.class);

        assertThat(result).contains("stored answer");
    }

    @Test
    void shouldRejectReusedKeyWithDifferentRequest() {
        PersistentIdempotencyContext context = context("new-request-hash");
        when(repository.findByUserIdAndOperationAndKeyHash(7L, "agent-chat", "key-hash"))
                .thenReturn(Optional.of(record("old-request-hash", "session-1", "\"answer\"")));

        assertThatThrownBy(() -> service.findCompleted(
                7L, "session-1", context, String.class))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void shouldRemoveExpiredRecordAndAllowReuse() {
        PersistentIdempotencyRecord expired = record(
                "request-hash", "session-1", "\"answer\"");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        PersistentIdempotencyContext context = context("request-hash");
        when(repository.findByUserIdAndOperationAndKeyHash(7L, "agent-chat", "key-hash"))
                .thenReturn(Optional.of(expired));

        Optional<String> result = service.findCompleted(
                7L, "session-1", context, String.class);

        assertThat(result).isEmpty();
        verify(repository).delete(expired);
        verify(repository).flush();
    }

    @Test
    void shouldCleanupExpiredRecords() {
        when(repository.deleteByExpiresAtBefore(org.mockito.ArgumentMatchers.any()))
                .thenReturn(3L);

        service.cleanupExpiredRecords();

        verify(repository).deleteByExpiresAtBefore(org.mockito.ArgumentMatchers.any());
    }

    private PersistentIdempotencyContext context(String requestHash) {
        return new PersistentIdempotencyContext("agent-chat", "key-hash", requestHash);
    }

    private PersistentIdempotencyRecord record(String requestHash,
                                                String sessionId,
                                                String payload) {
        return PersistentIdempotencyRecord.builder()
                .userId(7L)
                .operation("agent-chat")
                .keyHash("key-hash")
                .requestHash(requestHash)
                .sessionId(sessionId)
                .responsePayload(payload)
                .expiresAt(LocalDateTime.now().plusHours(1))
                .build();
    }
}
