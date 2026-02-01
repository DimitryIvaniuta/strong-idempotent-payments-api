# Strong Idempotent Payments API (Anti Double‑Charge)

Production‑grade Spring Boot service implementing **strong idempotency** for payment charges:

- `X-Idempotency-Key` + **request hash** (`Base64(SHA‑256(canonicalJson(body)))`)
- Stores **(scope, key, hash, response)** in Postgres and replays the **exact same** response on retries/double‑clicks
- Same key + different body ⇒ **409 Conflict**
- **Postgres advisory locks** (`pg_advisory_xact_lock`) serialize concurrent requests for the same key **even before** the idempotency row exists
- Writes a payment event to an **outbox** table in the same transaction (atomic DB state + event)
- **Outbox dispatcher** publishes to Kafka with **ack wait**, **retry/backoff**, and **DEAD** status after max attempts
- Optional Redis cache for faster replays (DB remains the source of truth)

## Tech
- Java 21, Spring Boot 3.5.10, Gradle
- Postgres + Flyway
- Kafka (KRaft in local docker-compose)
- Redis (optional cache)
- Micrometer/Actuator (+ Prometheus registry)

## Run locally (Docker Compose)
```bash
docker compose up -d
./gradlew bootRun
```

Service: `http://localhost:8080`

Health: `http://localhost:8080/actuator/health`

## API

### Charge (idempotent)
`POST /api/payments/charges`

Headers:
- `X-Idempotency-Key: <string>` (required, `[A-Za-z0-9._:-]`, max 128)
- `X-Correlation-Id: <optional>` (propagated to logs)
- Response headers:
  - `X-Idempotency-Replayed: true` when the response was replayed
  - `X-Idempotency-Request-Hash: <hash>` for observability/debugging
  - `Location: /api/payments/{paymentId}` for 201 responses (best effort)

Body:
```json
{
  "customerId": "cust_123",
  "amount": 100,
  "currency": "PLN",
  "paymentMethodToken": "pm_abc",
  "description": "order #123"
}
```

### Get payment
`GET /api/payments/{paymentId}`

## Local infrastructure
- Postgres: `localhost:5432` (db/user/password `payments`)
- Redis: `localhost:6379`
- Kafka: `localhost:9092`

## Tests
```bash
./gradlew test
```

Integration tests use **Testcontainers Postgres** and mock Kafka sends. They validate:
- same key replays exact response
- same key + different body → 409
- concurrent double-click produces exactly one payment

## Postman
`postman/Strong-Idempotent-Payments-API.postman_collection.json`  
`postman/Strong-Idempotent-Payments-API.postman_environment.json`

Import both and run the requests in order.
