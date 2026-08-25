package com.aiagent.auth.api;

import com.aiagent.auth.domain.User;
import com.aiagent.auth.infrastructure.repository.UserRepository;
import com.aiagent.infrastructure.security.JwtTokenProvider;
import com.aiagent.infrastructure.security.JwtRevocationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.aiagent.shared.exception.GlobalExceptionHandler;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {
    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private JwtRevocationService jwtRevocationService;
    private AuthController authController;

    @BeforeEach void setUp() {
        authController = new AuthController(
                userRepository, passwordEncoder, jwtTokenProvider, jwtRevocationService);
    }

    @Test void shouldLoginSuccessfully() {
        User user = User.builder().username("test").password("encoded").enabled(true).build();
        when(userRepository.findByUsername("test")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass", "encoded")).thenReturn(true);
        when(jwtTokenProvider.generateToken("test")).thenReturn("jwt-token");
        ResponseEntity<?> resp = authController.login(new LoginRequest("test", "pass"));
        assertEquals(200, resp.getStatusCode().value());
    }
    @Test void shouldRejectInvalidCredentials() {
        when(userRepository.findByUsername("bad")).thenReturn(Optional.empty());
        ResponseEntity<?> resp = authController.login(new LoginRequest("bad", "wrong"));
        assertEquals(401, resp.getStatusCode().value());
    }
    @Test void shouldRejectWrongPassword() {
        User user = User.builder().username("test").password("encoded").enabled(true).build();
        when(userRepository.findByUsername("test")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "encoded")).thenReturn(false);
        ResponseEntity<?> resp = authController.login(new LoginRequest("test", "wrong"));
        assertEquals(401, resp.getStatusCode().value());
    }
    @Test void shouldRejectDisabledAccount() {
        User user = User.builder().username("test").password("encoded").enabled(false).build();
        when(userRepository.findByUsername("test")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass", "encoded")).thenReturn(true);
        ResponseEntity<?> resp = authController.login(new LoginRequest("test", "pass"));
        assertEquals(403, resp.getStatusCode().value());
    }
    @Test void shouldRegisterSuccessfully() {
        when(userRepository.findByUsername("new")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password1")).thenReturn("encoded");
        ResponseEntity<?> resp = authController.register(
                new RegisterRequest("new", "password1", null));
        assertEquals(201, resp.getStatusCode().value());
        verify(userRepository).saveAndFlush(any(User.class));
    }
    @Test void shouldRejectDuplicateUsername() {
        when(userRepository.findByUsername("existing")).thenReturn(Optional.of(User.builder().build()));
        ResponseEntity<?> resp = authController.register(
                new RegisterRequest("existing", "password1", null));
        assertEquals(409, resp.getStatusCode().value());
    }

    @Test void shouldHandleConcurrentDuplicateRegistration() {
        when(userRepository.findByUsername("new-user")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("password1")).thenReturn("encoded");
        when(userRepository.saveAndFlush(any(User.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));

        ResponseEntity<?> resp = authController.register(
                new RegisterRequest("new-user", "password1", null));

        assertEquals(409, resp.getStatusCode().value());
    }

    @Test void shouldLogoutBearerToken() {
        ResponseEntity<Void> response = authController.logout("Bearer token");

        assertEquals(204, response.getStatusCode().value());
        verify(jwtRevocationService).revoke("token");
    }

    @Test void shouldReturnBadRequestForInvalidRegistrationPayload() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(authController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType("application/json")
                        .content("{\"username\":\"a!\",\"password\":\"short\",\"email\":\"bad\"}"))
                .andExpect(status().isBadRequest());
    }
}
