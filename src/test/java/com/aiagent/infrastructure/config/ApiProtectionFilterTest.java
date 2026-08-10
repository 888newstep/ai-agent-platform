package com.aiagent.infrastructure.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiProtectionFilterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private FilterChain filterChain;

    private AiProperties aiProperties;
    private ApiProtectionFilter filter;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.expire(anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        lenient().when(valueOperations.increment(anyString(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(1, Long.class));
        filter = new ApiProtectionFilter(redisTemplate, aiProperties);
    }

    @Test
    void shouldAllowProtectedRequestWithinRateAndCostBudgets() throws Exception {
        MockHttpServletRequest request = request("你好，请介绍 RAG");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldRejectWhenRequestRateLimitIsExceeded() throws Exception {
        aiProperties.getProtection().getRateLimit().setRequestsPerMinute(1);
        when(valueOperations.increment(anyString(), eq(1L))).thenReturn(2L);
        MockHttpServletRequest request = request("hello");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        assertThat(response.getContentAsString()).contains("rate_limit_exceeded");
    }

    @Test
    void shouldRejectWhenEstimatedTokenBudgetIsExceeded() throws Exception {
        aiProperties.getProtection().getCostBudget().setEstimatedTokensPerMinute(200);
        MockHttpServletRequest request = request("hello");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(valueOperations.increment(anyString(), eq(1L))).thenReturn(1L);
        when(valueOperations.increment(anyString(), eq(261L))).thenReturn(261L);

        filter.doFilter(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("cost_budget_exceeded");
    }

    @Test
    void shouldRejectOversizedInputBeforeCallingRedis() throws Exception {
        aiProperties.getProtection().getCostBudget().setMaxInputCharacters(2);
        MockHttpServletRequest request = request("hello");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(valueOperations, never()).increment(anyString(), anyLong());
        verify(filterChain, never()).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("request_too_large");
    }

    @Test
    void shouldFailOpenWhenRedisIsUnavailableByDefault() throws Exception {
        doThrow(new IllegalStateException("Redis down"))
                .when(valueOperations).increment(anyString(), anyLong());
        MockHttpServletRequest request = request("hello");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest request(String question) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agent/chat");
        request.setRemoteAddr("192.0.2.10");
        request.addParameter("question", question);
        return request;
    }
}