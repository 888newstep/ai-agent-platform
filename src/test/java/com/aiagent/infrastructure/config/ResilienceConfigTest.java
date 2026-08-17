package com.aiagent.infrastructure.config;

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
