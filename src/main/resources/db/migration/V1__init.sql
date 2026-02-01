-- Payments
CREATE TABLE IF NOT EXISTS payments (
  id               VARCHAR(36)  PRIMARY KEY,
  idempotency_key  VARCHAR(128) NOT NULL UNIQUE,
  customer_id      VARCHAR(128) NOT NULL,
  amount           BIGINT       NOT NULL,
  currency         VARCHAR(8)   NOT NULL,
  payment_method_token VARCHAR(128) NOT NULL,
  description      VARCHAR(512),
  status           VARCHAR(32)  NOT NULL,
  created_at       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_payments_customer_id ON payments(customer_id);

-- Idempotency records
-- NOTE: We scope keys to the endpoint ("payments:charge") to avoid accidental collisions across APIs.
CREATE TABLE IF NOT EXISTS idempotency_records (
  id               VARCHAR(36)  PRIMARY KEY,
  scope            VARCHAR(64)  NOT NULL,
  idempotency_key  VARCHAR(128) NOT NULL,
  request_hash     VARCHAR(88)  NOT NULL,
  status           VARCHAR(32)  NOT NULL,
  http_status      INT,
  response_body    TEXT,
  payment_id       VARCHAR(36),
  created_at       TIMESTAMPTZ  NOT NULL,
  updated_at       TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_idempotency_scope_key ON idempotency_records(scope, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_idempotency_created_at ON idempotency_records(created_at);

-- Outbox events
-- Production-grade fields: retry attempts, next attempt time, and last error.
CREATE TABLE IF NOT EXISTS outbox_events (
  id             VARCHAR(36) PRIMARY KEY,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id   VARCHAR(64) NOT NULL,
  event_type     VARCHAR(64) NOT NULL,
  event_key      VARCHAR(128) NOT NULL,
  payload        TEXT NOT NULL,
  status         VARCHAR(16) NOT NULL,
  attempt_count  INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ,
  last_error     TEXT,
  created_at     TIMESTAMPTZ NOT NULL,
  updated_at     TIMESTAMPTZ NOT NULL,
  sent_at        TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_next_created ON outbox_events(status, next_attempt_at, created_at);
