package com.github.dimitryivaniuta.gateway.payments.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Postgres advisory lock service.
 *
 * <p>Why we use this:
 * <ul>
 *   <li>Row locks only work after a row exists.</li>
 *   <li>During the first request for a given idempotency key, concurrent requests could race to INSERT.</li>
 * </ul>
 *
 * <p>Using {@code pg_advisory_xact_lock} we serialize all requests for the same (scope,key) within the current
 * DB transaction, even when the idempotency row does not yet exist.</p>
 *
 * <p>Locks are automatically released when the transaction ends.</p>
 */
@Component
public class PostgresAdvisoryLockService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the service.
     *
     * @param jdbcTemplate jdbc template
     */
    public PostgresAdvisoryLockService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Acquires a transaction-scoped advisory lock for the given scope and idempotency key.
     *
     * @param scope operation scope
     * @param idempotencyKey client-provided key
     */
    public void lock(String scope, String idempotencyKey) {
        long lockId = toLongHash(scope + "|" + idempotencyKey);
        jdbcTemplate.queryForObject("select pg_advisory_xact_lock(?)", Long.class, lockId);
    }

    private long toLongHash(String s) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(s.getBytes(StandardCharsets.UTF_8));
            // Use the first 8 bytes as signed long.
            return ByteBuffer.wrap(hash, 0, 8).getLong();
        } catch (Exception e) {
            // Extremely unlikely; fallback to Java hashCode (still safe, but higher collision probability).
            return s.hashCode();
        }
    }
}
