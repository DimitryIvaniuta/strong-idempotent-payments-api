package com.github.dimitryivaniuta.gateway.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimitryivaniuta.gateway.payments.domain.OutboxEvent;
import com.github.dimitryivaniuta.gateway.payments.domain.Payment;
import com.github.dimitryivaniuta.gateway.payments.repo.OutboxEventRepository;
import com.github.dimitryivaniuta.gateway.payments.repo.PaymentRepository;
import com.github.dimitryivaniuta.gateway.payments.service.events.PaymentChargedEvent;
import com.github.dimitryivaniuta.gateway.payments.web.dto.ChargeRequest;
import com.github.dimitryivaniuta.gateway.payments.web.dto.PaymentResponse;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business service for payment operations.
 */
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final PaymentProcessor paymentProcessor;
    private final ObjectMapper objectMapper;

    /**
     * Creates the service.
     *
     * @param paymentRepository payment repository
     * @param outboxEventRepository outbox repository
     * @param paymentProcessor payment processor abstraction
     * @param objectMapper jackson mapper
     */
    public PaymentService(
            PaymentRepository paymentRepository,
            OutboxEventRepository outboxEventRepository,
            PaymentProcessor paymentProcessor,
            ObjectMapper objectMapper
    ) {
        this.paymentRepository = paymentRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.paymentProcessor = paymentProcessor;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates a payment and writes an outbox event in the same transaction.
     *
     * <p>Propagation is MANDATORY: idempotency orchestration must own the transaction so that:
     * <ul>
     *   <li>payment row</li>
     *   <li>outbox row</li>
     *   <li>idempotency record update</li>
     * </ul>
     * are committed atomically.</p>
     *
     * @param idempotencyKey idempotency key
     * @param request charge request
     * @return response DTO
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentResponse createPaymentAndOutbox(String idempotencyKey, ChargeRequest request) {
        // Extra safety net (unique index on payments.idempotency_key also protects against duplicates).
        Payment existing = paymentRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return PaymentResponse.from(existing);
        }

        Payment payment = paymentProcessor.authorize(idempotencyKey, request);
        paymentRepository.save(payment);

        PaymentResponse response = PaymentResponse.from(payment);

        PaymentChargedEvent event = new PaymentChargedEvent(
                "1",
                UUID.randomUUID().toString(),
                Instant.now(),
                payment.getId(),
                idempotencyKey,
                payment.getCustomerId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus().name(),
                payment.getDescription()
        );

        outboxEventRepository.save(OutboxEvent.newEvent(
                "Payment",
                payment.getId(),
                "PaymentCharged",
                payment.getId(), // partition key
                toJson(event)
        ));

        return response;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize outbox payload", e);
        }
    }
}
