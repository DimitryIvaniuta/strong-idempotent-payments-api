package com.github.dimitryivaniuta.gateway.payments.domain;

/**
 * Outbox delivery status.
 *
 * <p>We deliberately keep this as a VARCHAR in the DB (no DB-level enum constraints) and enforce values in code.</p>
 */
public enum OutboxStatus {
    /** Newly created, never attempted. */
    NEW,
    /** Failed before; should be retried after {@code nextAttemptAt}. */
    RETRY,
    /** Successfully published to Kafka. */
    SENT,
    /** Permanently failed (after max attempts). */
    DEAD
}
