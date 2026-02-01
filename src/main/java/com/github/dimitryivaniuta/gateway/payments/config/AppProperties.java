package com.github.dimitryivaniuta.gateway.payments.config;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application-level configuration properties.
 *
 * <p>We use {@link ConfigurationProperties} instead of sprinkling {@code @Value} across the codebase,
 * which is easier to validate, test, and evolve.</p>
 */
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class AppProperties {

    private final Idempotency idempotency = new Idempotency();
    private final Outbox outbox = new Outbox();

    @Getter
    @Setter
    public static class Idempotency {
        /**
         * Scope for the charge endpoint.
         */
        private String chargeScope = "payments:charge";

        /**
         * Max age after which an IN_PROGRESS record is considered stale and can be safely re-processed
         * (because our business effect is transactional in Postgres).
         */
        private Duration staleInProgressAfter = Duration.ofSeconds(30);
    }

    @Getter
    @Setter
    public static class Outbox {
        /**
         * Kafka topic name for payment events.
         */
        private String paymentsEventsTopic = "payments-events";

        /**
         * Max number of events per batch.
         */
        private int batchSize = 100;

        /**
         * Fixed delay between publisher runs in milliseconds.
         */
        private long publishIntervalMs = 1000L;

        /**
         * Kafka send acknowledgment timeout.
         */
        private Duration sendTimeout = Duration.ofSeconds(5);

        /**
         * Max number of send attempts before moving to DEAD.
         */
        private int maxAttempts = 10;

        /**
         * Base backoff used for retries (exponential).
         */
        private Duration baseBackoff = Duration.ofSeconds(1);

        /**
         * Maximum backoff cap.
         */
        private Duration maxBackoff = Duration.ofMinutes(2);
    }
}
