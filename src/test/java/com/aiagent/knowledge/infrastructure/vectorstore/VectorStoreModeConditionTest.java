package com.aiagent.knowledge.infrastructure.vectorstore;

import com.aiagent.infrastructure.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class VectorStoreModeConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MilvusQaVectorStoreService.class, MilvusVectorStoreService.class)
            .withBean(AiProperties.class, AiProperties::new)
            .withPropertyValues(
                    "ai.vector-store.type=milvus",
                    "ai.vector-store.mode=qa");

    @Test
    void qaModeSelectsQaAdapter() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(VectorStoreService.class);
            assertThat(context).hasSingleBean(MilvusQaVectorStoreService.class);
            assertThat(context).doesNotHaveBean(MilvusVectorStoreService.class);
        });
    }
}
