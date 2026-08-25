package com.aiagent.infrastructure.security;

import com.aiagent.auth.domain.User;
import com.aiagent.auth.infrastructure.repository.UserRepository;
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
import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private UserRepository userRepository;
    @Mock private JwtRevocationService jwtRevocationService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;
    @Mock private FilterChain filterChain;
    private JwtAuthenticationFilter filter;

    @BeforeEach void setUp() {
        filter = new JwtAuthenticationFilter(jwtTokenProvider, userRepository, jwtRevocationService);
        SecurityContextHolder.clearContext();
    }

    @Test void shouldSetAuthWhenValidToken() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid");
        when(jwtTokenProvider.validateToken("valid")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken("valid")).thenReturn("user");
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(
                User.builder().username("user").enabled(true).build()));
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

    @Test void shouldNotSetAuthForDisabledUser() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid");
        when(jwtTokenProvider.validateToken("valid")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromToken("valid")).thenReturn("user");
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(
                User.builder().username("user").enabled(false).build()));

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test void shouldNotSetAuthForRevokedToken() throws Exception {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid");
        when(jwtTokenProvider.validateToken("valid")).thenReturn(true);
        when(jwtRevocationService.isRevoked("valid")).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(userRepository, never()).findByUsername(anyString());
    }
}
