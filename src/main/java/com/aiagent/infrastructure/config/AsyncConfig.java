package com.aiagent.infrastructure.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Slf4j
@Configuration
@EnableAsync
@RequiredArgsConstructor
public class AsyncConfig {

    private final AiProperties aiProperties;

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        AiProperties.AsyncThreadPool config = aiProperties.getPerformance().getAsyncThreadPool();

        int coreSize = Math.max(1, config.getCoreSize());
        int maxSize = Math.max(coreSize, config.getMaxSize());
        int queueCapacity = Math.max(1, config.getQueueCapacity());
        if (config.getCoreSize() <= 0 || config.getMaxSize() <= 0 || config.getQueueCapacity() <= 0) {
            log.warn("异步线程池配置包含非法值（coreSize={}, maxSize={}, queueCapacity={}），已自动修正为 ({}, {}, {})",
                    config.getCoreSize(), config.getMaxSize(), config.getQueueCapacity(),
                    coreSize, maxSize, queueCapacity);
        }

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("ai-agent-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.setAllowCoreThreadTimeOut(true);
        executor.initialize();

        log.info("异步线程池初始化完成: coreSize={}, maxSize={}, queueCapacity={}",
                coreSize, maxSize, queueCapacity);

        return executor;
    }
    
    @Bean(name = "ioTaskExecutor")
    public Executor ioTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ai-agent-io-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        
        log.info("IO密集型线程池初始化完成");
        
        return executor;
    }
}
