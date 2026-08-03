package com.aiagent.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AdminApiKeyFilter adminApiKeyFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .httpBasic(httpBasic -> httpBasic.disable())
            .formLogin(form -> form.disable())
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .exceptionHandling(exception -> exception
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Missing or invalid admin API key\"}");
                })
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/v1/agent/health").permitAll()
                .requestMatchers("/api/v1/agent/", "/api/v1/agent/admin", "/admin.html", "/error").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/agent/session", "/api/v1/agent/chat", "/api/v1/agent/react/chat", "/api/v1/agent/document/search").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/api/v1/agent/session/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/agent/chat/stream").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/agent/document/**").authenticated()
                .requestMatchers(
                        "/api/v1/agent/multi-agent/**",
                        "/api/v1/agent/document/upload",
                        "/api/v1/agent/cache",
                        "/api/v1/agent/evaluate",
                        "/api/v1/ecommerce/**",
                        "/api/cs/**"
                ).authenticated()
                .anyRequest().permitAll()
            )
            .addFilterBefore(adminApiKeyFilter, AnonymousAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
