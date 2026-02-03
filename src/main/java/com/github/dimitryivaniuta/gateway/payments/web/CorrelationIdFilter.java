package com.github.dimitryivaniuta.gateway.payments.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Adds/propagates a correlation id for request tracing.
 *
 * <p>Header: {@code X-Correlation-Id}. If missing, a new UUID is generated.</p>
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    /**
     * Header name for correlation id.
     */
    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    /**
     * MDC key.
     */
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String correlationId = Optional.ofNullable(request.getHeader(CORRELATION_ID_HEADER))
                .filter(v -> !v.isBlank())
                .orElse(UUID.randomUUID().toString());

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(CORRELATION_ID_HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
