-- =========================================================
-- V6: Refunds — created automatically when a paid booking is
-- cancelled; reviewed and actioned by the admin (Razorpay refund).
-- =========================================================

CREATE TABLE refunds (
    id                    BIGSERIAL PRIMARY KEY,
    booking_id            BIGINT       NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    customer_id           BIGINT       NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    amount_paise          BIGINT       NOT NULL,
    reason                TEXT,
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    razorpay_payment_id   VARCHAR(100),
    razorpay_refund_id    VARCHAR(100),
    admin_note            TEXT,
    requested_at          TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uq_refunds_booking_id UNIQUE (booking_id)
);

CREATE INDEX idx_refunds_status ON refunds(status);
CREATE INDEX idx_refunds_customer_id ON refunds(customer_id);
