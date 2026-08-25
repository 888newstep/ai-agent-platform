package com.aiagent.chat.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SseServiceTest {

    private SseService sseService;

    @BeforeEach
    void setUp() {
        sseService = new SseService();
    }

    @AfterEach
    void tearDown() {
        sseService.destroy();
    }

    @Test
    void shouldCreateEmitter() {
        SseEmitter emitter = sseService.createEmitter("session1");

        assertNotNull(emitter);
        assertEquals(1, sseService.getActiveCount());
    }

    @Test
    void shouldRejectBlankSessionId() {
        assertThrows(IllegalArgumentException.class, () -> sseService.createEmitter(" "));
    }

    @Test
    void shouldReturnFalseWhenSendingToNonExistent() {
        assertFalse(sseService.send("nonexistent", "data"));
    }

    @Test
    void shouldReturnFalseForInvalidEvent() {
        assertFalse(sseService.sendEvent("session", " ", "data"));
    }

    @Test
    void shouldCompleteSession() {
        sseService.createEmitter("session1");
        assertEquals(1, sseService.getActiveCount());

        sseService.complete("session1");

        assertEquals(0, sseService.getActiveCount());
    }

    @Test
    void shouldHandleMissingSessionCompletion() {
        assertDoesNotThrow(() -> sseService.complete("nonexistent"));
        assertDoesNotThrow(() -> sseService.completeWithError("nonexistent", new RuntimeException("err")));
    }

    @Test
    void shouldReplaceAndClosePreviousEmitterWithoutRemovingNewConnection() {
        sseService.destroy();
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        ArrayDeque<SseEmitter> emitters = new ArrayDeque<>();
        emitters.add(first);
        emitters.add(second);
        sseService = new SseService(emitters::remove);
        ArgumentCaptor<Runnable> firstCompletion = ArgumentCaptor.forClass(Runnable.class);

        sseService.createEmitter("same-session");
        verify(first).onCompletion(firstCompletion.capture());
        sseService.createEmitter("same-session");

        verify(first).complete();
        assertEquals(1, sseService.getActiveCount());

        firstCompletion.getValue().run();
        assertEquals(1, sseService.getActiveCount());
    }

    @Test
    void shouldTrackMultipleSessions() {
        sseService.createEmitter("s1");
        sseService.createEmitter("s2");
        sseService.createEmitter("s3");

        assertEquals(3, sseService.getActiveCount());
    }
}
