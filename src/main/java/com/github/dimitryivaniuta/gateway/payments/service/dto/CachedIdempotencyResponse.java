package com.github.dimitryivaniuta.gateway.payments.service.dto;

/**
 * Cached idempotency response (Redis optimization).
 *
 * @param requestHash request hash used for validation
 * @param httpStatus http status code
 * @param responseBodyJson stored response body
 */
public record CachedIdempotencyResponse(String requestHash, int httpStatus, String responseBodyJson) {}
