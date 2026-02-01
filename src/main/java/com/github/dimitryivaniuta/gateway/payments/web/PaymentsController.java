package com.github.dimitryivaniuta.gateway.payments.web;

import com.github.dimitryivaniuta.gateway.payments.domain.Payment;
import com.github.dimitryivaniuta.gateway.payments.repo.PaymentRepository;
import com.github.dimitryivaniuta.gateway.payments.service.IdempotencyService;
import com.github.dimitryivaniuta.gateway.payments.service.RequestHashService;
import com.github.dimitryivaniuta.gateway.payments.service.dto.IdempotentResult;
import com.github.dimitryivaniuta.gateway.payments.web.dto.ChargeRequest;
import com.github.dimitryivaniuta.gateway.payments.web.dto.PaymentResponse;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for payments.
 */
@RestController
@RequestMapping("/api/payments")
public class PaymentsController {

    /** Header used to provide an idempotency key. */
    public static final String IDEMPOTENCY_KEY_HEADER = "X-Idempotency-Key";

    /** Header returned when the response was replayed. */
    public static final String IDEMPOTENCY_REPLAYED_HEADER = "X-Idempotency-Replayed";

    /** Header returning the request hash for observability/debugging. */
    public static final String IDEMPOTENCY_REQUEST_HASH_HEADER = "X-Idempotency-Request-Hash";

    private static final int IDEMPOTENCY_KEY_MAX = 128;
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final IdempotencyService idempotencyService;
    private final PaymentRepository paymentRepository;
    private final RequestHashService requestHashService;

    /**
     * Creates the controller.
     *
     * @param idempotencyService idempotency service
     * @param paymentRepository payment repository
     * @param requestHashService request hash service (for returning the hash as a header)
     */
    public PaymentsController(IdempotencyService idempotencyService, PaymentRepository paymentRepository, RequestHashService requestHashService) {
        this.idempotencyService = idempotencyService;
        this.paymentRepository = paymentRepository;
        this.requestHashService = requestHashService;
    }

    /**
     * Charges a payment idempotently.
     *
     * @param idempotencyKey idempotency key (required)
     * @param request request
     * @return response (stored/replayed)
     */
    @PostMapping(value = "/charges", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> charge(
            @RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody ChargeRequest request
    ) {
        String normalizedKey = normalizeKeyOrThrow(idempotencyKey);
        String requestHash = requestHashService.hash(request);

        IdempotentResult result = idempotencyService.charge(normalizedKey, request);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(IDEMPOTENCY_KEY_HEADER, normalizedKey);
        headers.set(IDEMPOTENCY_REQUEST_HASH_HEADER, requestHash);
        if (result.replayed()) {
            headers.set(IDEMPOTENCY_REPLAYED_HEADER, "true");
        }

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus()).headers(headers);

        // For Created responses, also expose a Location header (best-effort)
        if (result.httpStatus() == 201) {
            try {
                String paymentId = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(result.responseBodyJson()).get("paymentId").asText();
                builder.location(URI.create("/api/payments/" + paymentId));
            } catch (Exception ignored) {
                // ignore
            }
        }

        return builder.body(result.responseBodyJson());
    }

    /**
     * Fetches a payment by id.
     *
     * @param paymentId payment id
     * @return payment response
     */
    @GetMapping(value = "/{paymentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PaymentResponse> get(@PathVariable String paymentId) {
        Payment p = paymentRepository.findById(paymentId).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found"));
        return ResponseEntity.ok(PaymentResponse.from(p));
    }

    private String normalizeKeyOrThrow(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ErrorResponseException(HttpStatus.BAD_REQUEST,
                    org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Missing X-Idempotency-Key header"),
                    null);
        }
        String k = raw.trim();
        if (k.length() > IDEMPOTENCY_KEY_MAX || !IDEMPOTENCY_KEY_PATTERN.matcher(k).matches()) {
            throw new ErrorResponseException(HttpStatus.BAD_REQUEST,
                    org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                            "Invalid X-Idempotency-Key. Allowed: [A-Za-z0-9._:-], max length " + IDEMPOTENCY_KEY_MAX),
                    null);
        }
        return k;
    }
}
