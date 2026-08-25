package com.aiagent.infrastructure.security;

import com.aiagent.shared.exception.AuthenticationServiceUnavailableException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtRevocationService {

    private static final String KEY_PREFIX = "ai:jwt:revoked:";

    private final StringRedisTemplate redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;

    public void revoke(String token) {
        Instant expiration = jwtTokenProvider.getExpiration(token);
        if (expiration == null) {
            throw new IllegalArgumentException("Invalid JWT token");
        }
        Duration ttl = Duration.between(Instant.now(), expiration);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(buildKey(token), "1", ttl);
        } catch (RuntimeException exception) {
            throw new AuthenticationServiceUnavailableException(
                    "Unable to revoke JWT token", exception);
        }
    }

    public boolean isRevoked(String token) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(buildKey(token)));
        } catch (RuntimeException exception) {
            log.warn("JWT revocation storage unavailable; continuing with signature validation: {}",
                    exception.getMessage());
            return false;
        }
    }

    private String buildKey(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
