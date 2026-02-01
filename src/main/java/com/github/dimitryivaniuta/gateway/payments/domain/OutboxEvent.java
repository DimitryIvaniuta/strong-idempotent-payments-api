package com.github.dimitryivaniuta.gateway.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Outbox event persisted in Postgres and published asynchronously to Kafka.
 *
 * <p>Outbox pattern guarantees "atomicity" between DB state change and event emission:
 * we write the event in the same transaction as the {@link Payment} update, and a separate publisher
 * sends it to Kafka with retries.</p>
 */
@Entity
@Table(
        name = "outbox_events",
        indexes = {
                @Index(name = "idx_outbox_status_next_created", columnList = "status,next_attempt_at,created_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "event_key", nullable = false, length = 128)
    private String eventKey;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private OutboxStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = true)
    private Instant nextAttemptAt;

    @Column(name = "last_error", nullable = true, columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "sent_at", nullable = true)
    private Instant sentAt;

    /**
     * Creates a new outbox event with status NEW.
     *
     * @param aggregateType aggregate type
     * @param aggregateId aggregate id
     * @param eventType event type
     * @param eventKey partition key (Kafka key)
     * @param payload JSON payload
     * @return event
     */
    public static OutboxEvent newEvent(String aggregateType, String aggregateId, String eventType, String eventKey, String payload) {
        OutboxEvent e = new OutboxEvent();
        e.id = UUID.randomUUID().toString();
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.eventType = eventType;
        e.eventKey = eventKey;
        e.payload = payload;
        e.status = OutboxStatus.NEW;
        e.attemptCount = 0;
        e.createdAt = Instant.now();
        e.updatedAt = e.createdAt;
        return e;
    }

    /**
     * Marks the event as sent.
     */
    public void markSent() {
        this.status = OutboxStatus.SENT;
        this.sentAt = Instant.now();
        this.updatedAt = this.sentAt;
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    /**
     * Marks as retryable failure.
     *
     * @param error error string
     * @param backoff backoff duration
     */
    public void markRetry(String error, Duration backoff) {
        this.status = OutboxStatus.RETRY;
        this.attemptCount++;
        this.lastError = error;
        this.nextAttemptAt = Instant.now().plus(backoff);
        this.updatedAt = Instant.now();
    }

    /**
     * Marks as permanently failed.
     *
     * @param error error string
     */
    public void markDead(String error) {
        this.status = OutboxStatus.DEAD;
        this.attemptCount++;
        this.lastError = error;
        this.nextAttemptAt = null;
        this.updatedAt = Instant.now();
    }
}
