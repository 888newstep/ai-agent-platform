package com.aiagent.infrastructure.security;

import com.aiagent.infrastructure.config.AiProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.time.Instant;
import java.util.UUID;

@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey key;
    private final long expirationMs;

    public JwtTokenProvider(AiProperties aiProperties) {
        String secret = aiProperties.getSecurity().getJwt().getSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("JWT_SECRET must contain at least 32 UTF-8 bytes");
        }
        if (aiProperties.getSecurity().getJwt().getExpiration() <= 0) {
            throw new IllegalStateException("JWT expiration must be greater than zero");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = aiProperties.getSecurity().getJwt().getExpiration();
    }

    public String generateToken(String username) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(key)
                .compact();
    }

    public String getUsernameFromToken(String token) {
        try {
            Claims claims = parseClaims(token);
            return claims.getSubject();
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Failed to extract username from token: {}", e.getMessage());
            return null;
        }
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }

    public Instant getExpiration(String token) {
        try {
            Date expiration = parseClaims(token).getExpiration();
            return expiration == null ? null : expiration.toInstant();
        } catch (JwtException | IllegalArgumentException exception) {
            log.warn("Failed to extract JWT expiration: {}", exception.getMessage());
            return null;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
