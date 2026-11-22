-- =========================================================
-- V9: Payment / PaymentTransaction ledger.
--
-- Payments are now created server-side the moment a Razorpay order is
-- requested (before the customer has paid anything), with the amount
-- computed entirely by PricingService. A Booking's amount_paise/payment_ref
-- can only ever be copied from a CAPTURED row here — see
-- BookingService#createForCustomer — so the frontend can no longer decide
-- what a booking costs.
-- =========================================================

CREATE TABLE payments (
    id                   BIGSERIAL PRIMARY KEY,
    payment_number       VARCHAR(32)  NOT NULL UNIQUE,
    customer_id          BIGINT       NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    booking_id           BIGINT       REFERENCES bookings(id) ON DELETE SET NULL,
    amount_paise         BIGINT       NOT NULL,
    currency             VARCHAR(8)   NOT NULL DEFAULT 'INR',
    status               VARCHAR(24)  NOT NULL DEFAULT 'CREATED',
    razorpay_order_id    VARCHAR(64)  NOT NULL UNIQUE,
    razorpay_payment_id  VARCHAR(64),
    service_id           VARCHAR(64),
    tier_id              VARCHAR(64),
    quotation_id         BIGINT       REFERENCES quotations(id),
    coupon_code          VARCHAR(32),
    created_at           TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at           TIMESTAMP    NOT NULL DEFAULT now(),
    captured_at          TIMESTAMP,
    failed_at            TIMESTAMP
);

CREATE INDEX idx_payments_customer ON payments(customer_id);
CREATE INDEX idx_payments_booking ON payments(booking_id);

CREATE TABLE payment_transactions (
    id                       BIGSERIAL PRIMARY KEY,
    payment_id               BIGINT      NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    type                      VARCHAR(32) NOT NULL,
    amount_paise              BIGINT      NOT NULL,
    status                    VARCHAR(24) NOT NULL,
    provider                  VARCHAR(24) NOT NULL DEFAULT 'razorpay',
    provider_transaction_id   VARCHAR(64),
    metadata                  TEXT,
    created_at                TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_transactions_payment ON payment_transactions(payment_id);
