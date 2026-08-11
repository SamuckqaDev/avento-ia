package com.avento;

import static org.assertj.core.api.Assertions.assertThat;

import com.avento.service.rag.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingAutoConfiguration;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.ai.vectorstore.redis.autoconfigure.RedisVectorStoreAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class RagServiceVectorStoreAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataRedisAutoConfiguration.class,
                    JacksonAutoConfiguration.class,
                    OllamaApiAutoConfiguration.class,
                    OllamaEmbeddingAutoConfiguration.class,
                    RedisVectorStoreAutoConfiguration.class))
            .withUserConfiguration(RagServiceConfiguration.class)
            .withPropertyValues(
                    "spring.data.redis.client-type=jedis",
                    "spring.ai.vectorstore.type=redis",
                    "spring.ai.vectorstore.redis.index-name=avento_index_nomic_embed_text",
                    "spring.ai.vectorstore.redis.initialize-schema=false",
                    "spring.ai.ollama.embedding.model=nomic-embed-text");

    @Test
    void injectsTheAutoConfiguredVectorStoreIntoRagServiceWithoutAWorkingRedisServer() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RedisVectorStore.class);
            assertThat(context).hasSingleBean(VectorStore.class);
            assertThat(context).hasSingleBean(RagService.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import(RagService.class)
    static class RagServiceConfiguration {}
}
