package com.github.dimitryivaniuta.gateway.payments;

import com.github.dimitryivaniuta.gateway.payments.repo.PaymentRepository;
import com.github.dimitryivaniuta.gateway.payments.web.PaymentsController;
import com.github.dimitryivaniuta.gateway.payments.web.dto.ChargeRequest;
import com.github.dimitryivaniuta.gateway.payments.web.dto.PaymentResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests verifying strong idempotency behavior using Postgres (Testcontainers).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotentPaymentsIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("payments")
            .withUsername("payments")
            .withPassword("payments");

    @BeforeAll
    static void start() {
        POSTGRES.start();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);

        // Avoid external deps in ITs
        r.add("spring.cache.type", () -> "none");
        r.add("spring.kafka.bootstrap-servers", () -> "localhost:0");
        r.add("app.outbox.publish-interval-ms", () -> "9999999"); // effectively disable
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    PaymentRepository paymentRepository;

    @MockBean
    KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void firstCallCreatesPayment_secondCallReplaysSameResponse_noDoubleCharge() {
        String key = "idem-" + UUID.randomUUID();
        ChargeRequest req = new ChargeRequest("cust1", 100L, "PLN", "pm_1", "test");

        ResponseEntity<String> r1 = postCharge(key, req);
        Assertions.assertEquals(201, r1.getStatusCodeValue());
        Assertions.assertNotNull(r1.getBody());

        ResponseEntity<String> r2 = postCharge(key, req);
        Assertions.assertEquals(201, r2.getStatusCodeValue());
        Assertions.assertEquals("true", r2.getHeaders().getFirst(PaymentsController.IDEMPOTENCY_REPLAYED_HEADER));
        Assertions.assertEquals(r1.getBody(), r2.getBody(), "Replay must return identical response body");

        Assertions.assertEquals(1, paymentRepository.count(), "Exactly one payment must be created");
    }

    @Test
    void sameKeyDifferentBodyReturnsConflict() {
        String key = "idem-" + UUID.randomUUID();
        ChargeRequest req1 = new ChargeRequest("cust1", 100L, "PLN", "pm_1", "A");
        ChargeRequest req2 = new ChargeRequest("cust1", 200L, "PLN", "pm_1", "B"); // different amount => different hash

        ResponseEntity<String> r1 = postCharge(key, req1);
        Assertions.assertEquals(201, r1.getStatusCodeValue());

        ResponseEntity<String> r2 = postCharge(key, req2);
        Assertions.assertEquals(409, r2.getStatusCodeValue());
        Assertions.assertEquals(1, paymentRepository.count());
    }

    @Test
    void concurrentDoubleClickStillCreatesSinglePayment_bothReceiveSameResponse() throws Exception {
        String key = "idem-" + UUID.randomUUID();
        ChargeRequest req = new ChargeRequest("cust2", 777L, "PLN", "pm_2", "double-click");

        var exec = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        final String[] bodies = new String[2];
        final int[] statuses = new int[2];

        for (int i = 0; i < 2; i++) {
            final int idx = i;
            exec.submit(() -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                ResponseEntity<String> resp = postCharge(key, req);
                statuses[idx] = resp.getStatusCodeValue();
                bodies[idx] = resp.getBody();
            });
        }

        Assertions.assertTrue(ready.await(5, TimeUnit.SECONDS));
        go.countDown();

        exec.shutdown();
        Assertions.assertTrue(exec.awaitTermination(10, TimeUnit.SECONDS));

        Assertions.assertEquals(201, statuses[0]);
        Assertions.assertEquals(201, statuses[1]);
        Assertions.assertEquals(bodies[0], bodies[1], "Both callers must see the same response");
        Assertions.assertEquals(1, paymentRepository.count(), "Exactly one payment must be created under concurrency");
    }

    private ResponseEntity<String> postCharge(String idempotencyKey, ChargeRequest req) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set(PaymentsController.IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        h.set("X-Correlation-Id", "it-" + UUID.randomUUID());
        return rest.exchange("http://localhost:" + port + "/api/payments/charges",
                HttpMethod.POST, new HttpEntity<>(req, h), String.class);
    }
}
