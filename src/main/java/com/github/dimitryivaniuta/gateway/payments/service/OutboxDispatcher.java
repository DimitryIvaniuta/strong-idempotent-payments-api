package com.github.dimitryivaniuta.gateway.payments.service;

import com.github.dimitryivaniuta.gateway.payments.config.AppProperties;
import com.github.dimitryivaniuta.gateway.payments.domain.OutboxEvent;
import com.github.dimitryivaniuta.gateway.payments.domain.OutboxStatus;
import com.github.dimitryivaniuta.gateway.payments.repo.OutboxEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production-grade outbox dispatcher.
 *
 * <p>Design points:
 * <ul>
 *   <li>Uses {@code FOR UPDATE SKIP LOCKED} so multiple instances can run concurrently without double-sending.</li>
 *   <li>Waits for Kafka acks (bounded by {@code sendTimeout}) before marking events SENT.</li>
 *   <li>Retries with exponential backoff + jitter; eventually marks events DEAD after {@code maxAttempts}.</li>
 * </ul>
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final AppProperties properties;

    private final Counter sentCounter;
    private final Counter retryCounter;
    private final Counter deadCounter;

    /**
     * Creates the dispatcher.
     *
     * @param outboxEventRepository repo
     * @param kafkaTemplate         template
     * @param properties            app properties
     * @param meterRegistry         metrics
     */
    public OutboxDispatcher(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            AppProperties properties,
            MeterRegistry meterRegistry
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;

        this.sentCounter = Counter.builder("payments.outbox.sent").register(meterRegistry);
        this.retryCounter = Counter.builder("payments.outbox.retry").register(meterRegistry);
        this.deadCounter = Counter.builder("payments.outbox.dead").register(meterRegistry);
    }

    /**
     * Periodically publishes pending outbox events.
     */
    @Scheduled(fixedDelayString = "${app.outbox.publish-interval-ms:1000}")
    @Transactional
    public void publishBatch() {
        AppProperties.Outbox outbox = properties.getOutbox();

        List<OutboxEvent> batch = outboxEventRepository.lockNextBatchForPublish(
                List.of(OutboxStatus.NEW.name(), OutboxStatus.RETRY.name()),
                Instant.now(),
                outbox.getBatchSize()
        );

        if (batch.isEmpty()) {
            return;
        }

        int sent = 0;
        int retry = 0;
        int dead = 0;

        for (OutboxEvent e : batch) {
            try {
                kafkaTemplate.send(outbox.getPaymentsEventsTopic(), e.getEventKey(), e.getPayload())
                        .get(outbox.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);

                e.markSent();
                sent++;
                sentCounter.increment();
            } catch (Exception ex) {
                String err = safeError(ex);

                if (e.getAttemptCount() + 1 >= outbox.getMaxAttempts()) {
                    e.markDead(err);
                    dead++;
                    deadCounter.increment();
                    log.error("Outbox event {} moved to DEAD after {} attempts. error={}", e.getId(), e.getAttemptCount(), err);
                } else {
                    Duration backoff = computeBackoff(outbox.getBaseBackoff(), outbox.getMaxBackoff(), e.getAttemptCount() + 1);
                    e.markRetry(err, backoff);
                    retry++;
                    retryCounter.increment();
                    log.warn("Outbox event {} failed. attempt={} nextAttemptAt={} error={}",
                            e.getId(), e.getAttemptCount(), e.getNextAttemptAt(), err);
                }
            }

            outboxEventRepository.save(e);
        }

        log.info("Outbox publish batch done. sent={} retry={} dead={} topic={}", sent, retry, dead, outbox.getPaymentsEventsTopic());
    }

    private Duration computeBackoff(Duration base, Duration max, int attempt) {
        // Exponential backoff: base * 2^(attempt-1), capped, with jitter [0.5..1.5]
        double exp = Math.pow(2.0, Math.max(0, attempt - 1));
        long candidateMs = (long) (base.toMillis() * exp);
        long capped = Math.min(candidateMs, max.toMillis());

        double jitter = 0.5 + (Math.random()); // 0.5..1.5
        long withJitter = (long) (capped * jitter);

        return Duration.ofMillis(Math.max(base.toMillis(), Math.min(withJitter, max.toMillis())));
    }

    private String safeError(Exception ex) {
        String msg = ex.getMessage();
        if (msg == null) {
            msg = ex.getClass().getSimpleName();
        }
        if (msg.length() > 2000) {
            msg = msg.substring(0, 2000);
        }
        return msg;
    }
}
