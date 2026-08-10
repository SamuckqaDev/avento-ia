package com.avento.service.rag;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisClient;

/**
 * Exposes the Redis client that Spring AI creates privately for its auto-configured vector store.
 *
 * <p>{@link VectorStoreResolver} needs the same client type to create an index for each configured
 * embedding profile. The connection factory remains the source of truth for Redis connectivity.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({JedisConnectionFactory.class, RedisClient.class})
@ConditionalOnBean(JedisConnectionFactory.class)
public class RedisVectorStoreClientConfiguration {

    @Bean
    RedisClient redisVectorStoreClient(JedisConnectionFactory connectionFactory) {
        DefaultJedisClientConfig.Builder clientConfig = DefaultJedisClientConfig.builder()
                .ssl(connectionFactory.isUseSsl())
                .clientName(connectionFactory.getClientName())
                .timeoutMillis(connectionFactory.getTimeout())
                .password(connectionFactory.getPassword())
                .database(connectionFactory.getDatabase());

        String username = connectionFactory.getStandaloneConfiguration().getUsername();
        if (username != null && !username.isBlank()) {
            clientConfig.user(username);
        }

        return RedisClient.builder()
                .hostAndPort(connectionFactory.getHostName(), connectionFactory.getPort())
                .clientConfig(clientConfig.build())
                .build();
    }
}
