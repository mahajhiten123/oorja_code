-- =========================================================
-- V10: Webhook idempotency ledger.
--
-- Razorpay explicitly documents that the same webhook event can be
-- delivered more than once, and recommends deduping on the
-- x-razorpay-event-id header. The unique (provider, event_id) constraint
-- below is what makes a duplicate delivery a no-op instead of double
-- -processing a payment.
-- =========================================================

CREATE TABLE webhook_events (
    id            BIGSERIAL PRIMARY KEY,
    provider      VARCHAR(24)  NOT NULL DEFAULT 'razorpay',
    event_id      VARCHAR(80)  NOT NULL,
    event_type    VARCHAR(64)  NOT NULL,
    received_at   TIMESTAMP    NOT NULL DEFAULT now(),
    processed_at  TIMESTAMP,
    status        VARCHAR(16)  NOT NULL DEFAULT 'RECEIVED',
    CONSTRAINT uq_webhook_events_provider_event UNIQUE (provider, event_id)
);
