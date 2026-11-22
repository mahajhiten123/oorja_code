-- =========================================================
-- V5: Quotation subsystem for installation quote requests
-- =========================================================

CREATE TABLE quotations (
    id               BIGSERIAL PRIMARY KEY,
    quotation_number VARCHAR(30)   NOT NULL UNIQUE,
    booking_id       BIGINT        NOT NULL UNIQUE REFERENCES bookings(id) ON DELETE CASCADE,
    customer_id      BIGINT        NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    subtotal         NUMERIC(12,2),
    discount_amount  NUMERIC(12,2) NOT NULL DEFAULT 0,
    tax_amount       NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_amount     NUMERIC(12,2),
    status           VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    valid_until      DATE,
    notes            TEXT,
    sent_at          TIMESTAMP,
    viewed_at        TIMESTAMP,
    responded_at     TIMESTAMP,
    payment_ref      VARCHAR(120),
    created_at       TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT now()
);

CREATE INDEX idx_quotations_customer_id ON quotations(customer_id);
CREATE INDEX idx_quotations_status      ON quotations(status);
CREATE INDEX idx_quotations_valid_until ON quotations(valid_until);
