package com.aiagent.infrastructure.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

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
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
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
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        
        log.info("IO密集型线程池初始化完成");
        
        return executor;
    }

    @Bean(name = "knowledgeMaintenanceExecutor")
    public Executor knowledgeMaintenanceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("knowledge-maintenance-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();

        log.info("知识库维护线程池初始化完成");
        return executor;
    }

    @Bean(name = "documentIngestionExecutor")
    public Executor documentIngestionExecutor() {
        AiProperties.Document config = aiProperties.getDocument();
        int coreSize = Math.max(1, config.getIngestionCoreSize());
        int maxSize = Math.max(coreSize, config.getIngestionMaxSize());
        int queueCapacity = Math.max(1, config.getIngestionQueueCapacity());

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("document-ingestion-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();

        log.info("文档摄取线程池初始化完成: coreSize={}, maxSize={}, queueCapacity={}",
                coreSize, maxSize, queueCapacity);
        return executor;
    }
}
