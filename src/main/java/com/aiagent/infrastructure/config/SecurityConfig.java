package com.aiagent.infrastructure.config;

import com.aiagent.infrastructure.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final AdminApiKeyFilter adminApiKeyFilter;
    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ApiProtectionFilter apiProtectionFilter;
    private final AiProperties aiProperties;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
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
                    response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Authentication required\"}");
                })
            )
            .authorizeHttpRequests(auth -> {
                auth
                    .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                    .requestMatchers("/api/v1/agent/health").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").hasRole("USER")
                    .requestMatchers("/api/v1/auth/**").permitAll()
                    .requestMatchers("/api/v1/agent/", "/api/v1/agent/admin", "/admin.html", "/error").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/info").permitAll();

                if (isPublicMetricsEnabled()) {
                    auth.requestMatchers("/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus").permitAll();
                }

                auth
                    .requestMatchers(HttpMethod.POST,
                            "/api/v1/agent/session",
                            "/api/v1/agent/chat",
                            "/api/v1/agent/react/chat",
                            "/api/v1/customer-support/**").hasRole("USER")
                    .requestMatchers(HttpMethod.GET,
                            "/api/v1/agent/chat/stream",
                            "/api/v1/agent/session/**").hasRole("USER")
                    .requestMatchers(HttpMethod.DELETE, "/api/v1/agent/session/**").hasRole("USER")
                    .requestMatchers(HttpMethod.POST, "/api/v1/agent/document/search")
                            .hasAnyRole("USER", "ADMIN")
                    .requestMatchers("/api/v1/agent/document/**").hasRole("ADMIN")
                    .requestMatchers(
                            "/api/v1/agent/multi-agent/**",
                            "/api/v1/agent/cache",
                            "/api/v1/agent/rag/**",
                            "/api/v1/agent/evaluate",
                            "/api/v1/agent/evaluate/**",
                            "/api/v1/ecommerce/**",
                            "/api/cs/**"
                    ).hasRole("ADMIN")
                    .anyRequest().authenticated();
            })
            .addFilterBefore(adminApiKeyFilter, AnonymousAuthenticationFilter.class)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(apiProtectionFilter, JwtAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        AiProperties.Security security = aiProperties.getSecurity();
        AiProperties.Cors cors = security == null || security.getCors() == null
                ? new AiProperties.Cors()
                : security.getCors();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(cors.getAllowedOrigins());
        configuration.setAllowedMethods(cors.getAllowedMethods());
        configuration.setAllowedHeaders(cors.getAllowedHeaders());
        configuration.setAllowCredentials(cors.isAllowCredentials());
        configuration.setMaxAge(cors.getMaxAge());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private boolean isPublicMetricsEnabled() {
        AiProperties.Observability observability = aiProperties.getObservability();
        return observability != null && observability.isPublicMetrics();
    }
}
