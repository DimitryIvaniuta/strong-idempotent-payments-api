package com.github.dimitryivaniuta.gateway.payments;

import com.github.dimitryivaniuta.gateway.payments.domain.OutboxEvent;
import com.github.dimitryivaniuta.gateway.payments.domain.OutboxStatus;
import com.github.dimitryivaniuta.gateway.payments.repo.OutboxEventRepository;
import com.github.dimitryivaniuta.gateway.payments.service.OutboxDispatcher;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Verifies that the outbox dispatcher publishes NEW events and marks them SENT (ack-based).
 */
@Testcontainers
@SpringBootTest
class OutboxDispatcherTest {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("payments")
            .withUsername("payments")
            .withPassword("payments");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.cache.type", () -> "none");
        r.add("spring.kafka.bootstrap-servers", () -> "localhost:0");
        r.add("app.outbox.publish-interval-ms", () -> "9999999"); // avoid background interference
    }

    @MockBean
    KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void dispatcherMarksSent(@org.springframework.beans.factory.annotation.Autowired OutboxEventRepository repo,
                             @org.springframework.beans.factory.annotation.Autowired OutboxDispatcher dispatcher) {

        OutboxEvent e = OutboxEvent.newEvent("Payment", "p1", "PaymentCharged", "p1", "{\"ok\":true}");
        repo.save(e);

        // Kafka send must return an already-completed future (ack success).
        CompletableFuture<SendResult<String, String>> ok = CompletableFuture.completedFuture(null);
        Mockito.when(kafkaTemplate.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString())).thenReturn(ok);

        dispatcher.publishBatch();

        OutboxEvent updated = repo.findById(e.getId()).orElseThrow();
        Assertions.assertEquals(OutboxStatus.SENT, updated.getStatus());

        ArgumentCaptor<String> topic = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        Mockito.verify(kafkaTemplate).send(topic.capture(), key.capture(), payload.capture());

        Assertions.assertEquals("payments-events", topic.getValue());
        Assertions.assertEquals("p1", key.getValue());
        Assertions.assertEquals("{\"ok\":true}", payload.getValue());
    }
}
