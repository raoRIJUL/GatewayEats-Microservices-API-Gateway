package com.example.gateway.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties({SharedRedisProperties.class, ResponseCacheRedisProperties.class})
public class RedisConfiguration {

    @Bean("sharedRedisConnectionFactory")
    @Primary
    LettuceConnectionFactory sharedRedisConnectionFactory(SharedRedisProperties properties) {
        return connectionFactory(
                properties.getHost(), properties.getPort(), properties.getDatabase(),
                properties.getPassword(), properties.getTimeout());
    }

    @Bean("responseCacheRedisConnectionFactory")
    LettuceConnectionFactory responseCacheRedisConnectionFactory(ResponseCacheRedisProperties properties) {
        return connectionFactory(
                properties.getHost(), properties.getPort(), properties.getDatabase(),
                properties.getPassword(), properties.getTimeout());
    }

    @Bean("sharedRedisTemplate")
    @Primary
    ReactiveStringRedisTemplate sharedRedisTemplate(
            @Qualifier("sharedRedisConnectionFactory") LettuceConnectionFactory connectionFactory
    ) {
        return new ReactiveStringRedisTemplate(connectionFactory);
    }

    @Bean("responseCacheRedisTemplate")
    ReactiveRedisTemplate<String, byte[]> responseCacheRedisTemplate(
            @Qualifier("responseCacheRedisConnectionFactory") LettuceConnectionFactory connectionFactory
    ) {
        RedisSerializer<String> keySerializer = RedisSerializer.string();
        RedisSerializer<byte[]> valueSerializer = RedisSerializer.byteArray();
        RedisSerializationContext<String, byte[]> context =
                RedisSerializationContext.<String, byte[]>newSerializationContext(keySerializer)
                        .value(valueSerializer)
                        .hashKey(keySerializer)
                        .hashValue(valueSerializer)
                        .build();
        return new ReactiveRedisTemplate<>(connectionFactory, context);
    }

    private LettuceConnectionFactory connectionFactory(
            String host,
            int port,
            int database,
            String password,
            java.time.Duration timeout
    ) {
        RedisStandaloneConfiguration redis = new RedisStandaloneConfiguration(host, port);
        redis.setDatabase(database);
        if (StringUtils.hasText(password)) {
            redis.setPassword(RedisPassword.of(password));
        }

        LettuceClientConfiguration client = LettuceClientConfiguration.builder()
                .commandTimeout(timeout)
                .shutdownTimeout(java.time.Duration.ZERO)
                .build();
        return new LettuceConnectionFactory(redis, client);
    }
}
