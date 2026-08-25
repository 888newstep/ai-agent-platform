package com.aiagent.auth.api;

import com.aiagent.auth.domain.User;
import com.aiagent.auth.infrastructure.repository.UserRepository;
import com.aiagent.infrastructure.security.JwtTokenProvider;
import com.aiagent.infrastructure.security.JwtRevocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.nio.charset.StandardCharsets;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final JwtRevocationService jwtRevocationService;

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest credentials) {
        String username = credentials.username().trim();
        String password = credentials.password();

        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            return ResponseEntity.status(401).body(Map.of("error", "Invalid credentials"));
        }

        if (!Boolean.TRUE.equals(user.getEnabled())) {
            return ResponseEntity.status(403).body(Map.of("error", "Account disabled"));
        }

        String token = jwtTokenProvider.generateToken(username);
        log.info("User logged in: {}", username);

        return ResponseEntity.ok(Map.of(
                "token", token,
                "username", username,
                "type", "Bearer"
        ));
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        String username = request.username().trim();
        String password = request.password();
        String email = StringUtils.hasText(request.email()) ? request.email().trim() : null;

        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Password exceeds BCrypt's 72-byte limit"));
        }

        if (userRepository.findByUsername(username).isPresent()) {
            return ResponseEntity.status(409).body(Map.of("error", "Username already exists"));
        }

        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(password))
                .email(email)
                .enabled(true)
                .build();

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            return ResponseEntity.status(409).body(Map.of("error", "Username already exists"));
        }
        log.info("User registered: {}", username);

        return ResponseEntity.status(201).body(Map.of("message", "User registered successfully"));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!StringUtils.hasText(authorization) || !authorization.startsWith("Bearer ")) {
            return ResponseEntity.badRequest().build();
        }
        jwtRevocationService.revoke(authorization.substring("Bearer ".length()));
        return ResponseEntity.noContent().build();
    }
}
