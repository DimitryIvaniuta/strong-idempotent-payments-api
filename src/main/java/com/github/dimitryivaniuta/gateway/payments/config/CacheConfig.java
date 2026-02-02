package com.github.dimitryivaniuta.gateway.payments.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;

import com.github.dimitryivaniuta.gateway.payments.service.dto.CachedIdempotencyResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.SimpleKeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Cache configuration.
 *
 * <p>Redis cache is an optimization only; Postgres is the source of truth for idempotency.</p>
 */
@EnableCaching
@Configuration
public class CacheConfig {

    /**
     * Cache name for idempotency responses.
     */
    public static final String IDEMPOTENCY_CACHE = "idempotencyResponse";

    /**
     * Cache manager using Redis with JSON serialization and a conservative TTL.
     *
     * @param factory      redis connection factory
     * @param objectMapper object mapper used for JSON serialization
     * @return cache manager
     */
    @Bean
    public RedisCacheManager cacheManager(
            RedisConnectionFactory factory,
            @Qualifier("canonicalObjectMapper") ObjectMapper objectMapper
    ) {
        var typedSerializer = new Jackson2JsonRedisSerializer<>(objectMapper, CachedIdempotencyResponse.class);

        var defaultCfg = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()));

        var idempotencyCfg = defaultCfg
                .entryTtl(Duration.ofMinutes(30))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(typedSerializer));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaultCfg)
                .withCacheConfiguration(IDEMPOTENCY_CACHE, idempotencyCfg)
                .build();
    }

    /**
     * Default key generator.
     *
     * @return key generator
     */
    @Bean
    public SimpleKeyGenerator keyGenerator() {
        return new SimpleKeyGenerator();
    }
}
