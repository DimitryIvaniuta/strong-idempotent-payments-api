package com.github.dimitryivaniuta.gateway.payments.service.dto;

/**
 * Result of an idempotent operation.
 *
 * @param httpStatus http status to return
 * @param responseBodyJson response body as JSON
 * @param replayed whether this was a replay
 */
public record IdempotentResult(int httpStatus, String responseBodyJson, boolean replayed) {}
