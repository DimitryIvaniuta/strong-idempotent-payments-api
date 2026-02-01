package com.github.dimitryivaniuta.gateway.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Payment entity representing a single business charge operation.
 *
 * <p>Idempotency is enforced by linking the payment to the idempotency key.</p>
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment {

    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128, unique = true)
    private String idempotencyKey;

    @Column(name = "customer_id", nullable = false, length = 128)
    private String customerId;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "currency", nullable = false, length = 8)
    private String currency;

    @Column(name = "payment_method_token", nullable = false, length = 128)
    private String paymentMethodToken;

    @Column(name = "description", length = 512)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Factory method.
     *
     * @param idempotencyKey idempotency key
     * @param customerId customer
     * @param amount amount in minor units
     * @param currency ISO currency
     * @param token payment method token
     * @param description description
     * @return payment entity
     */
    public static Payment newAuthorized(String idempotencyKey, String customerId, Long amount, String currency, String token, String description) {
        Payment p = new Payment();
        p.id = UUID.randomUUID().toString();
        p.idempotencyKey = idempotencyKey;
        p.customerId = customerId;
        p.amount = amount;
        p.currency = currency;
        p.paymentMethodToken = token;
        p.description = description;
        p.status = PaymentStatus.AUTHORIZED;
        p.createdAt = Instant.now();
        return p;
    }
}
