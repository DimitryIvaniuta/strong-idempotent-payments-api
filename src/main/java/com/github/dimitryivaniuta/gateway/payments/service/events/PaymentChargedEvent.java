package com.github.dimitryivaniuta.gateway.payments.service.events;

import java.time.Instant;

/**
 * Event emitted when a payment is successfully created/authorized.
 *
 * <p>Stored in the outbox and later published to Kafka.</p>
 */
public record PaymentChargedEvent(
        String schemaVersion,
        String eventId,
        Instant occurredAt,
        String paymentId,
        String idempotencyKey,
        String customerId,
        long amount,
        String currency,
        String status,
        String description
) {}
