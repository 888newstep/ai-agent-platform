package com.aiagent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 基础应用启动测试
 *
 * 验证 Spring 上下文能够正常加载，所有 Bean 初始化无冲突。
 * 使用 test 配置禁用外部依赖（Milvus、Redis 等），确保 CI 环境中可运行。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "ai.vector-store.milvus.host=localhost",
        "ai.vector-store.milvus.port=19530",
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379",
        "spring.datasource.url=jdbc:h2:mem:testdb",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "ai.model.provider=local",
        "ai.embedding.provider=local",
        "ai.model.local.base-url=http://localhost:11434/v1",
        "ai.model.local.model-name=dummy",
        "ai.model.local.api-key=dummy",
        "ai.model.deepseek.api-key=dummy",
        "ai.model.qianwen.api-key=dummy",
        "ai.model.doubao.api-key=dummy",
        "ai.model.qwen3-flash.api-key=dummy",
        "ai.embedding.local.base-url=http://localhost:11434/v1",
        "ai.embedding.local.model-name=dummy",
        "ai.embedding.local.dimension=1024",
        "ai.embedding.siliconflow.api-key=dummy",
        "jwt.secret=test-jwt-secret-for-ci-only-must-be-long-enough-32-chars"
})
class AiAgentApplicationTests {

    @Test
    void contextLoads() {
        // 验证 Spring 上下文启动成功
    }
}