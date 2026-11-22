-- =========================================================
-- V17: Production refund engine.
--
-- Turns the single-step "admin clicks Initiate" refund into a real
-- pipeline with an auditable state machine:
--
--   REQUESTED -> UNDER_REVIEW -> APPROVED -> PROCESSING -> COMPLETED
--                     |                          |
--                     +-> REJECTED               +-> FAILED -> (retry) APPROVED
--   REQUESTED -> CANCELLED      (customer withdrew it to rebook instead)
--
-- Everything money-related is stored in paise as BIGINT. The old
-- `amount_paise` column keeps its meaning but is now explicitly the NET
-- amount actually sent to the gateway; the gross and every deduction that
-- produced it are stored alongside so an invoice can be reconstructed
-- years later without re-running the policy engine (which will have
-- changed by then).
-- =========================================================

ALTER TABLE refunds
    ADD COLUMN refund_number          VARCHAR(32),
    ADD COLUMN payment_id             BIGINT REFERENCES payments(id) ON DELETE SET NULL,
    ADD COLUMN reason_code            VARCHAR(40)  NOT NULL DEFAULT 'CUSTOMER_REQUEST',
    ADD COLUMN requested_by_type      VARCHAR(16)  NOT NULL DEFAULT 'CUSTOMER',
    ADD COLUMN requested_by_id        BIGINT,
    ADD COLUMN policy_tier            VARCHAR(40),
    ADD COLUMN eligibility_note       TEXT,
    ADD COLUMN gross_amount_paise     BIGINT,
    ADD COLUMN refund_percent_bps     INT          NOT NULL DEFAULT 10000,
    ADD COLUMN cancellation_fee_paise BIGINT       NOT NULL DEFAULT 0,
    ADD COLUMN gateway_fee_paise      BIGINT       NOT NULL DEFAULT 0,
    ADD COLUMN auto_approved          BOOLEAN      NOT NULL DEFAULT FALSE,
    ADD COLUMN approved_by            VARCHAR(120),
    ADD COLUMN approved_at            TIMESTAMP,
    ADD COLUMN rejected_at            TIMESTAMP,
    ADD COLUMN attempt_count          INT          NOT NULL DEFAULT 0,
    ADD COLUMN last_error             TEXT,
    ADD COLUMN next_attempt_at        TIMESTAMP,
    ADD COLUMN idempotency_key        VARCHAR(80),
    ADD COLUMN gateway_status         VARCHAR(32),
    ADD COLUMN speed                  VARCHAR(16)  NOT NULL DEFAULT 'normal',
    ADD COLUMN expected_settlement_at TIMESTAMP,
    ADD COLUMN reconciled_at          TIMESTAMP,
    ADD COLUMN row_version            BIGINT       NOT NULL DEFAULT 0;

-- Backfill existing rows so nothing is left half-described.
UPDATE refunds SET gross_amount_paise = amount_paise WHERE gross_amount_paise IS NULL;
UPDATE refunds SET refund_number   = 'RFD-LEG-' || LPAD(id::text, 6, '0') WHERE refund_number IS NULL;
UPDATE refunds SET idempotency_key = 'legacy-refund-' || id              WHERE idempotency_key IS NULL;
UPDATE refunds SET policy_tier     = 'LEGACY', eligibility_note = 'Created before the refund policy engine existed.'
    WHERE policy_tier IS NULL;

-- Old status vocabulary -> new one.
UPDATE refunds SET status = 'REQUESTED'  WHERE status = 'PENDING';
UPDATE refunds SET status = 'PROCESSING' WHERE status = 'INITIATED';

-- Link legacy refunds to the payment row that funded them, where we can.
UPDATE refunds r
   SET payment_id = p.id
  FROM payments p
 WHERE r.payment_id IS NULL
   AND p.razorpay_payment_id IS NOT NULL
   AND p.razorpay_payment_id = r.razorpay_payment_id;

ALTER TABLE refunds ALTER COLUMN refund_number      SET NOT NULL;
ALTER TABLE refunds ALTER COLUMN idempotency_key    SET NOT NULL;
ALTER TABLE refunds ALTER COLUMN gross_amount_paise SET NOT NULL;
ALTER TABLE refunds ADD CONSTRAINT uq_refunds_number          UNIQUE (refund_number);
ALTER TABLE refunds ADD CONSTRAINT uq_refunds_idempotency_key UNIQUE (idempotency_key);
ALTER TABLE refunds ADD CONSTRAINT ck_refunds_amounts_non_negative
    CHECK (amount_paise >= 0 AND gross_amount_paise >= 0
           AND cancellation_fee_paise >= 0 AND gateway_fee_paise >= 0);
ALTER TABLE refunds ADD CONSTRAINT ck_refunds_net_within_gross
    CHECK (amount_paise <= gross_amount_paise);

-- A booking used to be allowed exactly one refund row, ever. That blocks
-- the "withdraw the refund and rebook instead, then change your mind again
-- next month" path, so the uniqueness is now scoped to refunds that are
-- still alive: a CANCELLED or REJECTED row no longer occupies the slot,
-- but you can still never have two in-flight refunds for one booking.
ALTER TABLE refunds DROP CONSTRAINT IF EXISTS uq_refunds_booking_id;
CREATE UNIQUE INDEX uq_refunds_booking_active ON refunds (booking_id)
    WHERE status IN ('REQUESTED', 'UNDER_REVIEW', 'APPROVED', 'PROCESSING', 'COMPLETED');

CREATE INDEX idx_refunds_payment        ON refunds (payment_id);
CREATE INDEX idx_refunds_razorpay_refund ON refunds (razorpay_refund_id);
CREATE INDEX idx_refunds_next_attempt   ON refunds (next_attempt_at) WHERE status IN ('APPROVED', 'FAILED');
CREATE INDEX idx_refunds_status_updated ON refunds (status, updated_at);

-- =========================================================
-- Append-only audit trail. One row per transition or notable side effect;
-- this is what the customer timeline and the ops console both read, and
-- nothing ever updates or deletes a row here.
-- =========================================================
CREATE TABLE refund_events (
    id              BIGSERIAL PRIMARY KEY,
    refund_id       BIGINT      NOT NULL REFERENCES refunds(id) ON DELETE CASCADE,
    type            VARCHAR(40) NOT NULL,
    from_status     VARCHAR(24),
    to_status       VARCHAR(24),
    actor_type      VARCHAR(16) NOT NULL DEFAULT 'SYSTEM',
    actor_label     VARCHAR(120),
    message         TEXT,
    amount_paise    BIGINT,
    provider_ref    VARCHAR(64),
    created_at      TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_refund_events_refund ON refund_events (refund_id, created_at);
