package com.avento.service.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.jedis.RedisClient;

class RedisVectorStoreClientConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(JedisConnectionFactory.class, RedisVectorStoreClientConfigurationTest::jedisConnectionFactory)
            .withUserConfiguration(VectorStoreResolverContext.class);

    @Test
    void publicaClienteRedisEUsaIndiceDoPerfilConfigurado() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RedisClient.class);

            VectorStore autoConfiguredStore = context.getBean(VectorStore.class);
            VectorStore resolvedStore = context.getBean(VectorStoreResolver.class).active();

            assertThat(resolvedStore).isNotSameAs(autoConfiguredStore);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({RedisVectorStoreClientConfiguration.class, VectorStoreResolver.class})
    static class VectorStoreResolverContext {

        @Bean
        VectorStore autoConfiguredStore() {
            return mock(VectorStore.class);
        }

        @Bean
        EmbeddingProfileSource embeddingProfileSource() {
            return () -> Optional.of(new EmbeddingProfile("bge-m3", mock(EmbeddingModel.class)));
        }
    }

    private static JedisConnectionFactory jedisConnectionFactory() {
        RedisStandaloneConfiguration standaloneConfiguration = new RedisStandaloneConfiguration("127.0.0.1", 6379);
        standaloneConfiguration.setUsername("avento");
        standaloneConfiguration.setPassword("secret");
        return new JedisConnectionFactory(standaloneConfiguration);
    }
}
