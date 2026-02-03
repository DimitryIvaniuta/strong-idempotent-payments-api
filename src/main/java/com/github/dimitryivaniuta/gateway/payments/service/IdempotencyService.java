package com.github.dimitryivaniuta.gateway.payments.service;

import com.github.dimitryivaniuta.gateway.payments.config.AppProperties;
import com.github.dimitryivaniuta.gateway.payments.service.dto.CachedIdempotencyResponse;
import com.github.dimitryivaniuta.gateway.payments.service.dto.IdempotentResult;
import com.github.dimitryivaniuta.gateway.payments.web.dto.ChargeRequest;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.web.ErrorResponseException;

/**
 * Idempotency entry-point used by the HTTP layer.
 *
 * <p>This service performs cheap pre-checks (hashing, cache fast-path) and delegates the transactional
 * "charge with locks" part to {@link IdempotencyChargeTxService} to avoid self-invocation issues with
 * {@code @Transactional}.</p>
 */
@Slf4j
@Service
public class IdempotencyService {

    private final IdempotencyCacheService cacheService;
    private final AppProperties properties;
    private final IdempotencyChargeTxService txService;

    private final Counter replayCounter;
    private final Counter conflictCounter;

    /**
     * Creates the service.
     */
    public IdempotencyService(
            IdempotencyCacheService cacheService,
            AppProperties properties,
            IdempotencyChargeTxService txService,
            MeterRegistry meterRegistry
    ) {
        this.cacheService = cacheService;
        this.properties = properties;
        this.txService = txService;

        this.replayCounter = Counter.builder("payments.idempotency.replayed").register(meterRegistry);
        this.conflictCounter = Counter.builder("payments.idempotency.conflict").register(meterRegistry);
    }

    /**
     * Executes a charge idempotently.
     *
     * @param idempotencyKey key
     * @param request        request payload
     * @return result containing HTTP status + response JSON + replay flag
     */
    public IdempotentResult charge(String idempotencyKey, String requestHash, ChargeRequest request) {
        String scope = properties.getIdempotency().getChargeScope();
        // requestHash is computed once at the HTTP edge to avoid double hashing.

        // Fast path: Redis cache (optimization)
        CachedIdempotencyResponse cached = cacheService.get(scope, idempotencyKey);
        if (cached != null) {
            if (!cached.requestHash().equals(requestHash)) {
                conflictCounter.increment();
                throw conflict(idempotencyKey);
            }
            replayCounter.increment();
            return new IdempotentResult(cached.httpStatus(), cached.responseBodyJson(), true);
        }

        // Transactional path (advisory lock + FOR UPDATE) in separate bean to ensure proxy is applied.
        return txService.chargeWithDbLock(scope, idempotencyKey, requestHash, request);
    }

    private ErrorResponseException conflict(String idempotencyKey) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Idempotency key '" + idempotencyKey + "' was already used with a different request payload.");
        return new ErrorResponseException(HttpStatus.CONFLICT, pd, null);
    }
}
