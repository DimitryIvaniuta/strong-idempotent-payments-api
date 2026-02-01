package com.github.dimitryivaniuta.gateway.payments.service;

import com.github.dimitryivaniuta.gateway.payments.config.AppProperties;
import com.github.dimitryivaniuta.gateway.payments.domain.IdempotencyRecord;
import com.github.dimitryivaniuta.gateway.payments.domain.IdempotencyStatus;
import com.github.dimitryivaniuta.gateway.payments.domain.Payment;
import com.github.dimitryivaniuta.gateway.payments.repo.IdempotencyRecordRepository;
import com.github.dimitryivaniuta.gateway.payments.repo.PaymentRepository;
import com.github.dimitryivaniuta.gateway.payments.service.dto.CachedIdempotencyResponse;
import com.github.dimitryivaniuta.gateway.payments.service.dto.IdempotentResult;
import com.github.dimitryivaniuta.gateway.payments.web.dto.ChargeRequest;
import com.github.dimitryivaniuta.gateway.payments.web.dto.PaymentResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

/**
 * Core idempotency orchestration.
 *
 * <p>Production-grade concurrency approach:
 * <ol>
 *   <li>Compute request hash</li>
 *   <li>Check Redis cache (optimization)</li>
 *   <li>Open DB transaction and acquire Postgres advisory lock for (scope,key)</li>
 *   <li>Load idempotency record (locked if present)</li>
 *   <li>If completed: replay</li>
 *   <li>If in progress and stale: recover safely (because business effect is transactional and also protected by
 *       a unique constraint on {@code payments.idempotency_key})</li>
 *   <li>If missing: create IN_PROGRESS record</li>
 *   <li>Execute business operation (create payment + outbox) and store response</li>
 * </ol>
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final PaymentRepository paymentRepository;
    private final RequestHashService requestHashService;
    private final PaymentService paymentService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final IdempotencyCacheService cacheService;
    private final PostgresAdvisoryLockService advisoryLockService;
    private final AppProperties properties;

    private final Counter createdCounter;
    private final Counter replayCounter;
    private final Counter conflictCounter;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Creates the service.
     */
    public IdempotencyService(
            IdempotencyRecordRepository idempotencyRecordRepository,
            PaymentRepository paymentRepository,
            RequestHashService requestHashService,
            PaymentService paymentService,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            IdempotencyCacheService cacheService,
            PostgresAdvisoryLockService advisoryLockService,
            AppProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.paymentRepository = paymentRepository;
        this.requestHashService = requestHashService;
        this.paymentService = paymentService;
        this.objectMapper = objectMapper;
        this.cacheService = cacheService;
        this.advisoryLockService = advisoryLockService;
        this.properties = properties;

        this.createdCounter = Counter.builder("payments.idempotency.created").register(meterRegistry);
        this.replayCounter = Counter.builder("payments.idempotency.replayed").register(meterRegistry);
        this.conflictCounter = Counter.builder("payments.idempotency.conflict").register(meterRegistry);
    }

    /**
     * Executes a charge idempotently.
     *
     * @param idempotencyKey key
     * @param request request payload
     * @return result containing HTTP status + response JSON + replay flag
     */
    public IdempotentResult charge(String idempotencyKey, ChargeRequest request) {
        String scope = properties.getIdempotency().getChargeScope();
        String requestHash = requestHashService.hash(request);

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

        return chargeWithDbLock(scope, idempotencyKey, requestHash, request);
    }

    /**
     * Executes the flow with transaction-scoped advisory locking + row locking.
     */
    @Transactional
    protected IdempotentResult chargeWithDbLock(String scope, String idempotencyKey, String requestHash, ChargeRequest request) {
        advisoryLockService.lock(scope, idempotencyKey);

        Optional<IdempotencyRecord> existingLocked = idempotencyRecordRepository.findByScopeAndKeyForUpdate(scope, idempotencyKey);

        if (existingLocked.isPresent()) {
            IdempotencyRecord record = existingLocked.get();

            if (!record.isSameHash(requestHash)) {
                conflictCounter.increment();
                throw conflict(idempotencyKey);
            }

            if (record.getStatus() == IdempotencyStatus.COMPLETED) {
                cacheService.put(scope, idempotencyKey, new CachedIdempotencyResponse(
                        requestHash,
                        record.getHttpStatus(),
                        record.getResponseBody()
                ));
                replayCounter.increment();
                return new IdempotentResult(record.getHttpStatus(), record.getResponseBody(), true);
            }

            // IN_PROGRESS: recover stale entries (process crash etc.)
            if (record.isStaleInProgress(properties.getIdempotency().getStaleInProgressAfter())) {
                log.warn("Recovering stale IN_PROGRESS idempotency record. scope={} key={}", scope, idempotencyKey);
                record.touch();
                idempotencyRecordRepository.save(record);

                // If payment already exists (unique by idempotency_key), complete record from DB.
                Payment existingPayment = paymentRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
                if (existingPayment != null) {
                    String responseJson = toJson(PaymentResponse.from(existingPayment));
                    record.complete(HttpStatus.CREATED.value(), responseJson, existingPayment.getId());
                    idempotencyRecordRepository.save(record);

                    cacheService.put(scope, idempotencyKey, new CachedIdempotencyResponse(requestHash, HttpStatus.CREATED.value(), responseJson));
                    replayCounter.increment();
                    return new IdempotentResult(HttpStatus.CREATED.value(), responseJson, true);
                }

                // Otherwise safe to re-run (transactional)
            } else {
                // Non-stale IN_PROGRESS should not occur because we serialize using advisory lock.
                // Still be defensive and return 409 to force retry.
                throw inProgress(idempotencyKey);
            }
        }

        // First request (or stale recovery): ensure record exists
        IdempotencyRecord record = existingLocked.orElseGet(() -> {
            IdempotencyRecord r = IdempotencyRecord.inProgress(scope, idempotencyKey, requestHash);
            idempotencyRecordRepository.save(r);
            entityManager.flush(); // ensure uniqueness early
            return r;
        });

        // Execute business operation (idempotencyKey is also stored on payment row as extra safety net)
        PaymentResponse response = paymentService.createPaymentAndOutbox(idempotencyKey, request);

        // Persist response for replay
        String responseJson = toJson(response);
        record.complete(HttpStatus.CREATED.value(), responseJson, response.paymentId());
        idempotencyRecordRepository.save(record);

        cacheService.put(scope, idempotencyKey, new CachedIdempotencyResponse(requestHash, HttpStatus.CREATED.value(), responseJson));
        createdCounter.increment();
        return new IdempotentResult(HttpStatus.CREATED.value(), responseJson, false);
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize response for idempotency storage", e);
        }
    }

    private ErrorResponseException conflict(String idempotencyKey) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Idempotency key '" + idempotencyKey + "' was already used with a different request payload.");
        return new ErrorResponseException(HttpStatus.CONFLICT, pd, null);
    }

    private ErrorResponseException inProgress(String idempotencyKey) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "Idempotency key '" + idempotencyKey + "' is currently being processed. Please retry with the same key.");
        return new ErrorResponseException(HttpStatus.CONFLICT, pd, null);
    }
}
