package com.github.dimitryivaniuta.gateway.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Idempotency record storing the request hash and the final HTTP response for a specific API scope.
 *
 * <p>Why scope matters:
 * <ul>
 *   <li>Idempotency keys are provided by clients and may be reused across different endpoints.</li>
 *   <li>We therefore scope keys to the API operation (default: {@code payments:charge}).</li>
 * </ul>
 *
 * <p>Key rules:
 * <ul>
 *   <li>Same (scope, key) + same request hash =&gt; replay stored response</li>
 *   <li>Same (scope, key) + different request hash =&gt; conflict (409)</li>
 * </ul>
 */
@Entity
@Table(
        name = "idempotency_records",
        uniqueConstraints = @UniqueConstraint(name = "uq_idempotency_scope_key", columnNames = {"scope", "idempotency_key"}),
        indexes = @Index(name = "idx_idempotency_created_at", columnList = "created_at")
)
@Getter
@Setter
@NoArgsConstructor
public class IdempotencyRecord {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "scope", nullable = false, updatable = false, length = 64)
    private String scope;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 88)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IdempotencyStatus status;

    @Column(name = "http_status", nullable = true)
    private Integer httpStatus;

    @Column(name = "response_body", nullable = true, columnDefinition = "text")
    private String responseBody;

    @Column(name = "payment_id", nullable = true, length = 36)
    private String paymentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Creates a new in-progress record.
     *
     * @param scope api scope (operation)
     * @param key idempotency key
     * @param hash request hash
     * @return record
     */
    public static IdempotencyRecord inProgress(String scope, String key, String hash) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(hash, "hash");

        IdempotencyRecord r = new IdempotencyRecord();
        r.id = UUID.randomUUID().toString();
        r.scope = scope;
        r.idempotencyKey = key;
        r.requestHash = hash;
        r.status = IdempotencyStatus.IN_PROGRESS;
        r.createdAt = Instant.now();
        r.updatedAt = r.createdAt;
        return r;
    }

    /**
     * Marks record as completed and stores the HTTP response.
     *
     * @param httpStatus http status code
     * @param responseBody response body JSON
     * @param paymentId payment id
     */
    public void complete(int httpStatus, String responseBody, String paymentId) {
        this.status = IdempotencyStatus.COMPLETED;
        this.httpStatus = httpStatus;
        this.responseBody = responseBody;
        this.paymentId = paymentId;
        this.updatedAt = Instant.now();
    }

    /**
     * Updates the timestamp (useful when re-processing a stale IN_PROGRESS record).
     */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    /**
     * Checks if the stored request hash matches an incoming request hash.
     *
     * @param incomingHash incoming hash
     * @return true if matches
     */
    public boolean isSameHash(String incomingHash) {
        return this.requestHash != null && this.requestHash.equals(incomingHash);
    }

    /**
     * Returns true if this record has been IN_PROGRESS longer than the given duration.
     *
     * <p>This can happen if the process crashed mid-flight. For our current implementation the business effect is
     * strictly DB-transactional, so a stale in-progress record is safe to re-process with the same request hash.</p>
     *
     * @param maxAge max allowed age
     * @return true if stale
     */
    public boolean isStaleInProgress(Duration maxAge) {
        if (status != IdempotencyStatus.IN_PROGRESS) {
            return false;
        }
        Instant ref = updatedAt != null ? updatedAt : createdAt;
        return ref != null && ref.isBefore(Instant.now().minus(maxAge));
    }
}
