package com.github.dimitryivaniuta.gateway.payments.domain;

/**
 * Payment status stored as a string in the database.
 */
public enum PaymentStatus {
    /** Payment created/authorized. */
    AUTHORIZED,

    /** Payment captured/settled. */
    CAPTURED,

    /** Payment failed. */
    FAILED
}
