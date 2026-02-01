package com.github.dimitryivaniuta.gateway.payments.service;

import com.github.dimitryivaniuta.gateway.payments.domain.Payment;
import com.github.dimitryivaniuta.gateway.payments.web.dto.ChargeRequest;
import org.springframework.stereotype.Component;

/**
 * Deterministic payment processor used for local development and tests.
 *
 * <p>Always authorizes the payment.</p>
 */
@Component
public class StubPaymentProcessor implements PaymentProcessor {

    @Override
    public Payment authorize(String idempotencyKey, ChargeRequest request) {
        return Payment.newAuthorized(
                idempotencyKey,
                request.customerId(),
                request.amount(),
                request.currency(),
                request.paymentMethodToken(),
                request.description()
        );
    }
}
