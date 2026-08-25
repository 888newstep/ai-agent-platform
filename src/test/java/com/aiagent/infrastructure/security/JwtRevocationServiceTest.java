package com.aiagent.infrastructure.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtRevocationServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private JwtRevocationService service;

    @BeforeEach
    void setUp() {
        service = new JwtRevocationService(redisTemplate, jwtTokenProvider);
    }

    @Test
    void shouldBlacklistTokenUntilExpiration() {
        when(jwtTokenProvider.getExpiration("token"))
                .thenReturn(Instant.now().plusSeconds(300));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.revoke("token");

        verify(valueOperations).set(
                argThat(key -> key.startsWith("ai:jwt:revoked:")),
                eq("1"),
                argThat((Duration duration) -> !duration.isNegative() && duration.toSeconds() <= 300));
    }

    @Test
    void shouldDetectRevokedToken() {
        when(redisTemplate.hasKey(anyString())).thenReturn(true);

        assertThat(service.isRevoked("token")).isTrue();
    }

    @Test
    void shouldFailOpenWhenRedisIsUnavailable() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new IllegalStateException("Redis down"));

        assertThat(service.isRevoked("token")).isFalse();
    }

    @Test
    void shouldFailLogoutWhenBlacklistCannotBeWritten() {
        when(jwtTokenProvider.getExpiration("token"))
                .thenReturn(Instant.now().plusSeconds(300));
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis down"))
                .when(valueOperations).set(anyString(), eq("1"), org.mockito.ArgumentMatchers.any(Duration.class));

        assertThatThrownBy(() -> service.revoke("token"))
                .isInstanceOf(com.aiagent.shared.exception.AuthenticationServiceUnavailableException.class);
    }
}
