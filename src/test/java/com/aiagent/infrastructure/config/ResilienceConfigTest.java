package com.aiagent.infrastructure.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResilienceConfigTest {
    @Test void shouldCreateCircuitBreakerRegistry() {
        assertNotNull(new ResilienceConfig().circuitBreakerRegistry());
    }
    @Test void shouldCreateRetryRegistry() {
        assertNotNull(new ResilienceConfig().retryRegistry());
    }
}
