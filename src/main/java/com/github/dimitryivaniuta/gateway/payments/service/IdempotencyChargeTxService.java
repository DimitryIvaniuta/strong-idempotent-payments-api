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
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Transactional executor for idempotent charge operation.
 *
 * <p><b>Why this class exists:</b> Spring {@code @Transactional} is applied via AOP proxies.
 * If a {@code @Transactional} method is invoked from within the same instance (self-invocation),
 * the proxy is bypassed and the transaction is NOT started. This service isolates the transactional
 * method behind a separate bean so the proxy is always used.</p>
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Acquire a Postgres transaction-scoped advisory lock for (scope, idempotencyKey).</li>
 *   <li>Lock idempotency row {@code FOR UPDATE} when present.</li>
 *   <li>Guarantee exactly-once business effect and persist the response for safe replays.</li>
 * </ul>
 * </p>
 */
@Service
public class IdempotencyChargeTxService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyChargeTxService.class);

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;
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
    public IdempotencyChargeTxService(
            IdempotencyRecordRepository idempotencyRecordRepository,
            PaymentRepository paymentRepository,
            PaymentService paymentService,
            ObjectMapper objectMapper,
            IdempotencyCacheService cacheService,
            PostgresAdvisoryLockService advisoryLockService,
            AppProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.paymentRepository = paymentRepository;
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
     * Executes a charge in a single DB transaction with advisory lock + row lock.
     *
     * <p>Important: this method MUST be invoked via Spring proxy (i.e., from another bean),
     * otherwise the transaction will not be started and {@code FOR UPDATE} / advisory locks will fail.</p>
     *
     * @param scope idempotency scope
     * @param idempotencyKey idempotency key
     * @param requestHash request hash
     * @param request request payload
     * @return idempotent result
     */
@Transactional
    public IdempotentResult chargeWithDbLock(String scope, String idempotencyKey, String requestHash, ChargeRequest request) {
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
