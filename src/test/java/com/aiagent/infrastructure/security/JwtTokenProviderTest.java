package com.aiagent.infrastructure.security;

import com.aiagent.infrastructure.config.AiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JwtTokenProviderTest {
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        AiProperties props = new AiProperties();
        props.getSecurity().getJwt().setSecret("test-secret-key-for-jwt-must-be-at-least-32-chars-long");
        props.getSecurity().getJwt().setExpiration(86400000);
        jwtTokenProvider = new JwtTokenProvider(props);
    }

    @Test void shouldGenerateToken() {
        String token = jwtTokenProvider.generateToken("testuser");
        assertNotNull(token); assertFalse(token.isEmpty());
    }
    @Test void shouldExtractUsername() {
        String token = jwtTokenProvider.generateToken("testuser");
        assertEquals("testuser", jwtTokenProvider.getUsernameFromToken(token));
    }
    @Test void shouldValidateValidToken() {
        String token = jwtTokenProvider.generateToken("testuser");
        assertTrue(jwtTokenProvider.validateToken(token));
    }
    @Test void shouldRejectInvalidToken() { assertFalse(jwtTokenProvider.validateToken("invalid")); }
    @Test void shouldRejectNullToken() { assertFalse(jwtTokenProvider.validateToken(null)); }
    @Test void shouldRejectInvalidExpirationConfiguration() {
        AiProperties p = new AiProperties();
        p.getSecurity().getJwt().setSecret("test-secret-key-for-jwt-must-be-at-least-32-chars-long");
        p.getSecurity().getJwt().setExpiration(-1000);
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(p));
    }
    @Test void shouldHandleDifferentUsernames() {
        String t1 = jwtTokenProvider.generateToken("u1");
        String t2 = jwtTokenProvider.generateToken("u2");
        assertNotEquals(t1, t2);
        assertEquals("u1", jwtTokenProvider.getUsernameFromToken(t1));
    }

    @Test void shouldGenerateUniqueTokensForSameUser() {
        assertNotEquals(
                jwtTokenProvider.generateToken("same-user"),
                jwtTokenProvider.generateToken("same-user"));
    }

    @Test void shouldExposeExpiration() {
        assertNotNull(jwtTokenProvider.getExpiration(jwtTokenProvider.generateToken("user")));
    }

    @Test void shouldEmbedIssuerAndAudience() throws Exception {
        String token = jwtTokenProvider.generateToken("testuser");
        String[] parts = token.split("\\.");
        String payloadJson = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(payloadJson.contains("\"iss\":\"newagent\""));
        assertTrue(payloadJson.contains("\"aud\":[\"newagent-api\"]"));
    }

    @Test void shouldRejectTokenWithWrongIssuer() throws Exception {
        AiProperties props = new AiProperties();
        props.getSecurity().getJwt().setSecret("test-secret-key-for-jwt-must-be-at-least-32-chars-long");
        props.getSecurity().getJwt().setExpiration(86400000);
        props.getSecurity().getJwt().setIssuer("attacker");
        JwtTokenProvider attackerProvider = new JwtTokenProvider(props);
        String forgedToken = attackerProvider.generateToken("testuser");
        assertFalse(jwtTokenProvider.validateToken(forgedToken));
    }
}
