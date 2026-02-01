package com.github.dimitryivaniuta.gateway.payments.web.dto;

import java.time.Instant;

/**
 * Generic error response.
 *
 * @param code machine-readable code
 * @param message human readable message
 * @param timestamp event time
 */
public record ErrorResponse(String code, String message, Instant timestamp) {}
