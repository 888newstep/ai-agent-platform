package com.aiagent.auth.api;

import com.aiagent.auth.domain.User;
import com.aiagent.auth.infrastructure.repository.UserRepository;
import com.aiagent.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider jwtTokenProvider;
    private AuthController authController;

    @BeforeEach void setUp() { authController = new AuthController(userRepository, passwordEncoder, jwtTokenProvider); }

    @Test void shouldLoginSuccessfully() {
        User user = User.builder().username("test").password("encoded").enabled(true).build();
        when(userRepository.findByUsername("test")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass", "encoded")).thenReturn(true);
        when(jwtTokenProvider.generateToken("test")).thenReturn("jwt-token");
        ResponseEntity<?> resp = authController.login(Map.of("username", "test", "password", "pass"));
        assertEquals(200, resp.getStatusCode().value());
    }
    @Test void shouldRejectInvalidCredentials() {
        when(userRepository.findByUsername("bad")).thenReturn(Optional.empty());
        ResponseEntity<?> resp = authController.login(Map.of("username", "bad", "password", "wrong"));
        assertEquals(401, resp.getStatusCode().value());
    }
    @Test void shouldRejectWrongPassword() {
        User user = User.builder().username("test").password("encoded").enabled(true).build();
        when(userRepository.findByUsername("test")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);
        ResponseEntity<?> resp = authController.login(Map.of("username", "test", "password", "wrong"));
        assertEquals(401, resp.getStatusCode().value());
    }
    @Test void shouldRejectDisabledAccount() {
        User user = User.builder().username("test").password("encoded").enabled(false).build();
        when(userRepository.findByUsername("test")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass", "encoded")).thenReturn(true);
        ResponseEntity<?> resp = authController.login(Map.of("username", "test", "password", "pass"));
        assertEquals(403, resp.getStatusCode().value());
    }
    @Test void shouldRejectMissingCredentials() {
        ResponseEntity<?> resp = authController.login(Map.of());
        assertEquals(400, resp.getStatusCode().value());
    }
    @Test void shouldRegisterSuccessfully() {
        when(userRepository.findByUsername("new")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("pass")).thenReturn("encoded");
        ResponseEntity<?> resp = authController.register(Map.of("username", "new", "password", "pass"));
        assertEquals(200, resp.getStatusCode().value());
        verify(userRepository).save(any(User.class));
    }
    @Test void shouldRejectDuplicateUsername() {
        when(userRepository.findByUsername("existing")).thenReturn(Optional.of(User.builder().build()));
        ResponseEntity<?> resp = authController.register(Map.of("username", "existing", "password", "pass"));
        assertEquals(400, resp.getStatusCode().value());
    }
    @Test void shouldRejectRegisterMissingFields() {
        ResponseEntity<?> resp = authController.register(Map.of());
        assertEquals(400, resp.getStatusCode().value());
    }
}
