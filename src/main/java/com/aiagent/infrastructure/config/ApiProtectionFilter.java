package com.aiagent.infrastructure.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 对高成本公开接口执行分布式请求限流和估算 Token 预算控制。
 *
 * <p>匿名请求按远端 IP 隔离，认证请求按 SecurityContext 中的主体隔离；不信任客户端自报租户 Header。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiProtectionFilter extends OncePerRequestFilter {

    private static final String KEY_PREFIX = "ai:protection:";
    private static final long WINDOW_SECONDS = 60;
    private static final Set<String> PROTECTED_POST_PATHS = Set.of(
            "/api/v1/agent/chat",
            "/api/v1/agent/react/chat",
            "/api/v1/customer-support/chat",
            "/api/v1/agent/document/search",
            "/api/v1/auth/login",
            "/api/v1/auth/register"
    );

    private final StringRedisTemplate redisTemplate;
    private final AiProperties aiProperties;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!isProtectedRequest(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        AiProperties.Protection protection = aiProperties.getProtection();
        if (protection == null) {
            filterChain.doFilter(request, response);
            return;
        }

        long inputCharacters = extractInputCharacterCount(request);
        AiProperties.CostBudget costBudget = protection.getCostBudget();
        if (costBudget.isEnabled() && inputCharacters > costBudget.getMaxInputCharacters()) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "request_too_large", "Request input exceeds the configured character limit", 0);
            return;
        }

        String identity = resolveIdentity(request);
        AiProperties.RateLimit rateLimit = protection.getRateLimit();
        boolean authenticationRequest = isAuthenticationRequest(request);
        int requestsPerMinute = authenticationRequest
                ? rateLimit.getAuthenticationRequestsPerMinute()
                : rateLimit.getRequestsPerMinute();
        if (rateLimit.isEnabled() && requestsPerMinute > 0) {
            ProtectionDecision decision = checkRateLimit(
                    identity, requestsPerMinute, authenticationRequest ? false : rateLimit.isFailOpen());
            if (!decision.allowed()) {
                reject(response, decision.status(), decision.code(), decision.message(), decision.retryAfterSeconds());
                return;
            }
        }

        if (authenticationRequest) {
            filterChain.doFilter(request, response);
            return;
        }

        if (costBudget.isEnabled() && costBudget.getEstimatedTokensPerMinute() > 0) {
            long estimatedTokens = estimateTokens(inputCharacters, costBudget);
            if (costBudget.getMaxEstimatedTokensPerRequest() > 0
                    && estimatedTokens > costBudget.getMaxEstimatedTokensPerRequest()) {
                reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                        "request_cost_limit_exceeded", "Estimated request cost exceeds the configured limit", 0);
                return;
            }
            ProtectionDecision decision = checkCostBudget(identity, estimatedTokens, costBudget);
            if (!decision.allowed()) {
                reject(response, decision.status(), decision.code(), decision.message(), decision.retryAfterSeconds());
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private ProtectionDecision checkRateLimit(String identity,
                                               int requestsPerMinute,
                                               boolean failOpen) {
        String key = buildWindowKey("requests", identity);
        try {
            long count = increment(key, 1);
            if (count > requestsPerMinute) {
                return ProtectionDecision.denied(
                        429,
                        "rate_limit_exceeded",
                        "Too many requests; retry after the current minute window",
                        retryAfterSeconds());
            }
            return ProtectionDecision.permit();
        } catch (RuntimeException ex) {
            log.warn("API rate-limit storage unavailable, failOpen={}: {}",
                    failOpen, ex.getMessage());
            return failOpen
                    ? ProtectionDecision.permit()
                    : ProtectionDecision.denied(
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "rate_limit_unavailable",
                    "Request protection is temporarily unavailable",
                    5);
        }
    }

    private ProtectionDecision checkCostBudget(String identity,
                                               long estimatedTokens,
                                               AiProperties.CostBudget costBudget) {
        String key = buildWindowKey("tokens", identity);
        try {
            long consumed = increment(key, estimatedTokens);
            if (consumed > costBudget.getEstimatedTokensPerMinute()) {
                return ProtectionDecision.denied(
                        429,
                        "cost_budget_exceeded",
                        "Estimated token budget exceeded; retry after the current minute window",
                        retryAfterSeconds());
            }
            return ProtectionDecision.permit();
        } catch (RuntimeException ex) {
            log.warn("API cost-budget storage unavailable, failOpen={}: {}",
                    costBudget.isFailOpen(), ex.getMessage());
            return costBudget.isFailOpen()
                    ? ProtectionDecision.permit()
                    : ProtectionDecision.denied(
                    HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "cost_budget_unavailable",
                    "Cost protection is temporarily unavailable",
                    5);
        }
    }

    private long increment(String key, long amount) {
        Long count = redisTemplate.opsForValue().increment(key, amount);
        if (count == null) {
            throw new IllegalStateException("Redis returned no counter value");
        }
        if (count == amount) {
            Boolean expirationSet = redisTemplate.expire(key, WINDOW_SECONDS + 5, TimeUnit.SECONDS);
            if (Boolean.FALSE.equals(expirationSet)) {
                redisTemplate.delete(key);
                throw new IllegalStateException("Redis counter expiration was not set");
            }
        }
        return count;
    }

    private boolean isProtectedRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) && "/api/v1/agent/chat/stream".equals(path)) {
            return true;
        }
        return "POST".equalsIgnoreCase(method) && PROTECTED_POST_PATHS.contains(path);
    }

    private boolean isAuthenticationRequest(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/api/v1/auth/login".equals(path) || "/api/v1/auth/register".equals(path);
    }

    private long extractInputCharacterCount(HttpServletRequest request) {
        String question = request.getParameter("question");
        if (StringUtils.hasText(question)) {
            return question.codePointCount(0, question.length());
        }
        String query = request.getParameter("query");
        if (StringUtils.hasText(query)) {
            return query.codePointCount(0, query.length());
        }
        if ("/api/v1/customer-support/chat".equals(request.getRequestURI())) {
            long contentLength = request.getContentLengthLong();
            if (contentLength < 0) {
                return (long) aiProperties.getProtection().getCostBudget().getMaxInputCharacters() + 1;
            }
            return contentLength;
        }
        return 0;
    }

    private long estimateTokens(long characterCount, AiProperties.CostBudget costBudget) {
        long contentTokens = (long) Math.ceil(characterCount * Math.max(costBudget.getTokensPerCharacter(), 0.01));
        return Math.max(1, costBudget.getPromptOverheadTokens()) + contentTokens;
    }

    private String resolveIdentity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String principal = authentication == null ? null : authentication.getName();
        String identity = authentication != null
                && authentication.isAuthenticated()
                && StringUtils.hasText(principal)
                && !"anonymousUser".equals(principal)
                ? "principal:" + principal
                : "ip:" + (StringUtils.hasText(request.getRemoteAddr()) ? request.getRemoteAddr() : "unknown");
        return sha256(identity);
    }

    private String buildWindowKey(String quotaType, String identity) {
        long window = Instant.now().getEpochSecond() / WINDOW_SECONDS;
        return KEY_PREFIX + quotaType + ":" + identity + ":" + window;
    }

    private long retryAfterSeconds() {
        long elapsed = Instant.now().getEpochSecond() % WINDOW_SECONDS;
        return Math.max(1, WINDOW_SECONDS - elapsed);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private void reject(HttpServletResponse response,
                        int status,
                        String code,
                        String message,
                        long retryAfterSeconds) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        if (retryAfterSeconds > 0) {
            response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        }
        response.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }

    private record ProtectionDecision(boolean allowed,
                                      int status,
                                      String code,
                                      String message,
                                      long retryAfterSeconds) {
        private static ProtectionDecision permit() {
            return new ProtectionDecision(true, 0, "", "", 0);
        }

        private static ProtectionDecision denied(int status,
                                                 String code,
                                                 String message,
                                                 long retryAfterSeconds) {
            return new ProtectionDecision(false, status, code, message, retryAfterSeconds);
        }
    }
}
