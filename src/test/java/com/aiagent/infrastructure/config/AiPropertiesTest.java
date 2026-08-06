package com.aiagent.infrastructure.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AiPropertiesTest {
    @Test void shouldCreateDefaults() {
        AiProperties p = new AiProperties();
        assertNotNull(p.getModel()); assertNotNull(p.getEmbedding()); assertNotNull(p.getVectorStore());
        assertNotNull(p.getDocument()); assertNotNull(p.getRag()); assertNotNull(p.getTool());
        assertNotNull(p.getSession()); assertNotNull(p.getSecurity()); assertNotNull(p.getPerformance());
    }
    @Test void shouldHaveModelDefaults() {
        AiProperties p = new AiProperties();
        assertEquals("deepseek", p.getModel().getProvider());
        assertNotNull(p.getModel().getDeepseek()); assertNotNull(p.getModel().getQianwen());
    }
    @Test void shouldHaveSecurityDefaults() {
        AiProperties p = new AiProperties();
        assertEquals(86400000, p.getSecurity().getJwt().getExpiration());
        assertEquals("X-Admin-Api-Key", p.getSecurity().getAdminHeaderName());
    }
    @Test void shouldHavePerformanceDefaults() {
        AiProperties p = new AiProperties();
        assertEquals(20, p.getPerformance().getAsyncThreadPool().getCoreSize());
        assertTrue(p.getPerformance().getCache().isEnabled());
    }
    @Test void shouldHaveSessionDefaults() {
        AiProperties p = new AiProperties();
        assertEquals(86400, p.getSession().getTtl());
        assertEquals(10, p.getSession().getSlidingWindowSize());
    }
    @Test void shouldHaveRagDefaults() {
        AiProperties p = new AiProperties();
        assertEquals(5, p.getRag().getTopK());
        assertTrue(p.getRag().isEnableHybridSearch());
    }
    @Test void shouldHaveToolDefaults() {
        AiProperties p = new AiProperties();
        assertTrue(p.getTool().isEnabled());
        assertEquals(100, p.getTool().getDatabaseQuery().getMaxRows());
    }
    @Test void shouldSetAndGet() {
        AiProperties p = new AiProperties();
        p.getModel().setProvider("local");
        assertEquals("local", p.getModel().getProvider());
        p.getSecurity().setAdminApiKey("key");
        assertEquals("key", p.getSecurity().getAdminApiKey());
    }
}
