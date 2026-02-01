package com.github.dimitryivaniuta.gateway.payments.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request payload for charging a payment.
 */
public record ChargeRequest(
        @NotBlank String customerId,
        @NotNull @Positive Long amount,
        @NotBlank String currency,
        @NotBlank String paymentMethodToken,
        String description
) {}
