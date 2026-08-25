package com.aiagent.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    @Test
    void shouldFailFastWhenGeneralAsyncPoolsAreSaturated() {
        AsyncConfig config = new AsyncConfig(new AiProperties());
        ThreadPoolTaskExecutor taskExecutor = (ThreadPoolTaskExecutor) config.taskExecutor();
        ThreadPoolTaskExecutor ioTaskExecutor = (ThreadPoolTaskExecutor) config.ioTaskExecutor();

        try {
            assertThat(taskExecutor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            assertThat(ioTaskExecutor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            taskExecutor.shutdown();
            ioTaskExecutor.shutdown();
        }
    }
}
