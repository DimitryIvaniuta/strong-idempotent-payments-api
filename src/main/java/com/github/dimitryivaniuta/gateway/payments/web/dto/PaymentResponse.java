package com.github.dimitryivaniuta.gateway.payments.web.dto;

import com.github.dimitryivaniuta.gateway.payments.domain.Payment;
import java.time.Instant;

/**
 * Response returned for a charge request.
 */
public record PaymentResponse(
        String paymentId,
        String status,
        Long amount,
        String currency,
        String customerId,
        String description,
        Instant createdAt
) {
    /**
     * Maps a domain {@link Payment} to an API response.
     *
     * @param p payment entity
     * @return response
     */
    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(
                p.getId(),
                p.getStatus().name(),
                p.getAmount(),
                p.getCurrency(),
                p.getCustomerId(),
                p.getDescription(),
                p.getCreatedAt()
        );
    }
}
