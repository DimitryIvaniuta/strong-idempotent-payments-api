package com.github.dimitryivaniuta.gateway.payments.service;

import com.github.dimitryivaniuta.gateway.payments.service.dto.CachedIdempotencyResponse;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import static com.github.dimitryivaniuta.gateway.payments.config.CacheConfig.IDEMPOTENCY_CACHE;

/**
 * Optional Redis-backed cache for completed idempotency responses.
 *
 * <p>Postgres remains the source of truth; cache misses fall back to DB.</p>
 */
@Service
public class IdempotencyCacheService {

    /**
     * Gets a cached response if present.
     *
     * @param scope operation scope
     * @param idempotencyKey idempotency key
     * @return cached response or null
     */
    @Cacheable(cacheNames = IDEMPOTENCY_CACHE, key = "T(String).valueOf(#scope).concat(':').concat(#idempotencyKey)", unless = "#result == null")
    public CachedIdempotencyResponse get(String scope, String idempotencyKey) {
        return null; // Spring Cache will bypass method body on cache hit.
    }

    /**
     * Stores a cached response.
     *
     * @param scope operation scope
     * @param idempotencyKey idempotency key
     * @param response cached response
     * @return response
     */
    @CachePut(cacheNames = IDEMPOTENCY_CACHE, key = "T(String).valueOf(#scope).concat(':').concat(#idempotencyKey)")
    public CachedIdempotencyResponse put(String scope, String idempotencyKey, CachedIdempotencyResponse response) {
        return response;
    }
}
