package com.aiagent.chat.infrastructure;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Slf4j
@Service
public class SseService {

    private static final long HEARTBEAT_INTERVAL_SECONDS = 15;
    private static final long SSE_TIMEOUT_MS = 300_000;

    private final Map<String, SseEmitter> activeEmitters = new ConcurrentHashMap<>();
    private final Supplier<SseEmitter> emitterFactory;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public SseService() {
        this(() -> new SseEmitter(SSE_TIMEOUT_MS));
    }

    SseService(Supplier<SseEmitter> emitterFactory) {
        this.emitterFactory = Objects.requireNonNull(emitterFactory, "emitterFactory must not be null");
        heartbeatScheduler.scheduleAtFixedRate(
                this::sendHeartbeats,
                HEARTBEAT_INTERVAL_SECONDS,
                HEARTBEAT_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
        log.info("SSE 服务初始化完成，心跳间隔: {}s", HEARTBEAT_INTERVAL_SECONDS);
    }

    public SseEmitter createEmitter(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }

        SseEmitter emitter = Objects.requireNonNull(emitterFactory.get(), "emitterFactory returned null");
        emitter.onCompletion(() -> {
            log.debug("SSE 连接完成: sessionId={}", sessionId);
            removeEmitter(sessionId, emitter);
        });
        emitter.onTimeout(() -> {
            log.warn("SSE 连接超时: sessionId={}", sessionId);
            removeEmitter(sessionId, emitter);
        });
        emitter.onError(error -> {
            log.warn("SSE 连接错误: sessionId={}, error={}", sessionId, error.getMessage());
            removeEmitter(sessionId, emitter);
        });

        SseEmitter previous = activeEmitters.put(sessionId, emitter);
        completeEmitter(previous);
        log.debug("SSE 连接创建: sessionId={}, 活跃连接数={}", sessionId, activeEmitters.size());
        return emitter;
    }

    public boolean send(String sessionId, String data) {
        return sendEvent(sessionId, "message", data);
    }

    public boolean sendEvent(String sessionId, String eventName, Object data) {
        if (!StringUtils.hasText(sessionId) || !StringUtils.hasText(eventName)) {
            return false;
        }
        SseEmitter emitter = activeEmitters.get(sessionId);
        if (emitter == null) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
            return true;
        } catch (IOException | IllegalStateException exception) {
            log.warn("SSE 发送事件失败: sessionId={}, event={}, error={}",
                    sessionId, eventName, exception.getMessage());
            removeEmitter(sessionId, emitter);
            completeEmitter(emitter);
            return false;
        }
    }

    public void complete(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        completeEmitter(removeEmitter(sessionId));
    }

    public void completeWithError(String sessionId, Throwable error) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        SseEmitter emitter = removeEmitter(sessionId);
        if (emitter != null) {
            emitter.completeWithError(error == null ? new IllegalStateException("SSE stream failed") : error);
        }
    }

    public int getActiveCount() {
        return activeEmitters.size();
    }

    private SseEmitter removeEmitter(String sessionId) {
        return activeEmitters.remove(sessionId);
    }

    private void removeEmitter(String sessionId, SseEmitter emitter) {
        activeEmitters.remove(sessionId, emitter);
    }

    private void completeEmitter(SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.complete();
        } catch (RuntimeException exception) {
            log.debug("SSE 连接已关闭: {}", exception.getMessage());
        }
    }

    private void sendHeartbeats() {
        activeEmitters.forEach((sessionId, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat").data(""));
            } catch (IOException | IllegalStateException exception) {
                log.warn("SSE 心跳发送失败: sessionId={}, error={}", sessionId, exception.getMessage());
                removeEmitter(sessionId, emitter);
                completeEmitter(emitter);
            }
        });
    }

    @PreDestroy
    public void destroy() {
        log.info("SSE 服务关闭，清理 {} 个活跃连接", activeEmitters.size());
        heartbeatScheduler.shutdownNow();
        activeEmitters.values().forEach(this::completeEmitter);
        activeEmitters.clear();
    }
}
