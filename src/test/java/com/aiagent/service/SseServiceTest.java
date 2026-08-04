package com.aiagent.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.junit.jupiter.api.Assertions.*;

class SseServiceTest {
    private SseService sseService;

    @BeforeEach void setUp() { sseService = new SseService(); }
    @AfterEach void tearDown() { sseService.destroy(); }

    @Test void shouldCreateEmitter() {
        SseEmitter emitter = sseService.createEmitter("session1");
        assertNotNull(emitter);
        assertEquals(1, sseService.getActiveCount());
    }

    @Test void shouldReturnFalseWhenSendingToNonExistent() {
        assertFalse(sseService.send("nonexistent", "data"));
    }

    @Test void shouldReturnFalseWhenSendingEventToNonExistent() {
        assertFalse(sseService.sendEvent("nonexistent", "event", "data"));
    }

    @Test void shouldCompleteSession() {
        sseService.createEmitter("session1");
        assertEquals(1, sseService.getActiveCount());
        sseService.complete("session1");
        assertEquals(0, sseService.getActiveCount());
    }

    @Test void shouldHandleCompleteNonExistent() {
        assertDoesNotThrow(() -> sseService.complete("nonexistent"));
    }

    @Test void shouldHandleCompleteWithErrorNonExistent() {
        assertDoesNotThrow(() -> sseService.completeWithError("nonexistent", new RuntimeException("err")));
    }

    @Test void shouldTrackMultipleSessions() {
        sseService.createEmitter("s1");
        sseService.createEmitter("s2");
        sseService.createEmitter("s3");
        assertEquals(3, sseService.getActiveCount());
    }

    @Test void shouldDestroyCleanly() {
        sseService.createEmitter("s1");
        sseService.createEmitter("s2");
        assertDoesNotThrow(() -> sseService.destroy());
    }
}
