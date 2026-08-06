package com.aiagent.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;
    private JwtAuthenticationFilter filter;

    @BeforeEach void setUp() {
        filter = new JwtAuthenticationFilter(jwtTokenProvider);
        SecurityContextHolder.clearContext();
    }

    @Test void shouldSetAuthWhenValidToken() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid");
        when(jwtTokenProvider.validateToken("valid")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken("valid")).thenReturn("user");
        filter.doFilterInternal(request, response, filterChain);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("user", SecurityContextHolder.getContext().getAuthentication().getName());
    }
    @Test void shouldNotSetAuthWhenNoToken() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);
        filter.doFilterInternal(request, response, filterChain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
    @Test void shouldNotSetAuthWhenInvalid() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer bad");
        when(jwtTokenProvider.validateToken("bad")).thenReturn(false);
        filter.doFilterInternal(request, response, filterChain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
    @Test void shouldNotSetAuthWhenNoBearer() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Basic token");
        filter.doFilterInternal(request, response, filterChain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
