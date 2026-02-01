package com.github.dimitryivaniuta.gateway.payments.domain;

/**
 * Idempotency record status.
 */
public enum IdempotencyStatus {
    /** In progress - the request is being processed. */
    IN_PROGRESS,

    /** Completed - response stored. */
    COMPLETED
}
