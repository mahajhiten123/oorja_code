-- =========================================================
-- V18: Customer-facing documents (tax invoices, credit notes,
-- service completion reports) and support ticketing.
--
-- Design note: invoices are *issued records*, not views. Once a row exists
-- here its figures never change, because a tax document that silently
-- re-computes itself when pricing changes is not a tax document. A refund
-- produces a separate CREDIT_NOTE row referencing the original invoice
-- rather than editing it.
-- =========================================================

CREATE TABLE invoices (
    id                  BIGSERIAL PRIMARY KEY,
    invoice_number      VARCHAR(32)  NOT NULL UNIQUE,
    type                VARCHAR(20)  NOT NULL DEFAULT 'TAX_INVOICE',
    status              VARCHAR(24)  NOT NULL DEFAULT 'ISSUED',

    customer_id         BIGINT       NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    booking_id          BIGINT       REFERENCES bookings(id) ON DELETE SET NULL,
    payment_id          BIGINT       REFERENCES payments(id) ON DELETE SET NULL,
    quotation_id        BIGINT       REFERENCES quotations(id) ON DELETE SET NULL,
    refund_id           BIGINT       REFERENCES refunds(id) ON DELETE SET NULL,
    -- A credit note always points at the invoice it reverses.
    original_invoice_id BIGINT       REFERENCES invoices(id) ON DELETE SET NULL,

    -- Frozen snapshot of who this was billed to, as it read on the day.
    bill_to_name        VARCHAR(160) NOT NULL,
    bill_to_email       VARCHAR(160),
    bill_to_phone       VARCHAR(32),
    bill_to_address     TEXT,
    place_of_supply     VARCHAR(80),

    description         TEXT         NOT NULL,
    subtotal_paise      BIGINT       NOT NULL,
    discount_paise      BIGINT       NOT NULL DEFAULT 0,
    tax_rate_bps        INT          NOT NULL DEFAULT 1800,
    tax_paise           BIGINT       NOT NULL DEFAULT 0,
    total_paise         BIGINT       NOT NULL,
    currency            VARCHAR(8)   NOT NULL DEFAULT 'INR',

    payment_method      VARCHAR(40),
    payment_reference   VARCHAR(80),
    paid_at             TIMESTAMP,

    issued_at           TIMESTAMP    NOT NULL DEFAULT now(),
    -- Cached rendered PDF; regenerated on demand if the object is missing.
    pdf_storage_key     VARCHAR(400),
    pdf_generated_at    TIMESTAMP,
    notes               TEXT,

    CONSTRAINT ck_invoices_totals CHECK (total_paise >= 0 AND subtotal_paise >= 0)
);

CREATE INDEX idx_invoices_customer ON invoices (customer_id, issued_at DESC);
CREATE INDEX idx_invoices_booking  ON invoices (booking_id);
-- One tax invoice per payment. Credit notes are excluded so a refund can
-- always add its own row against the same payment.
CREATE UNIQUE INDEX uq_invoices_payment_tax ON invoices (payment_id)
    WHERE type = 'TAX_INVOICE' AND payment_id IS NOT NULL;
CREATE UNIQUE INDEX uq_invoices_refund ON invoices (refund_id)
    WHERE refund_id IS NOT NULL;

-- =========================================================
-- Service completion reports. Filled in by the technician/admin when a
-- booking is closed out; the customer downloads it as a PDF. Photos are
-- not duplicated here — they're the booking's existing attachments, which
-- already have storage, retention and soft-delete handled.
-- =========================================================
CREATE TABLE service_reports (
    id                    BIGSERIAL PRIMARY KEY,
    report_number         VARCHAR(32)  NOT NULL UNIQUE,
    booking_id            BIGINT       NOT NULL UNIQUE REFERENCES bookings(id) ON DELETE CASCADE,
    customer_id           BIGINT       NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    technician_id         BIGINT       REFERENCES technicians(id) ON DELETE SET NULL,

    service_date          DATE         NOT NULL,
    started_at            TIMESTAMP,
    finished_at           TIMESTAMP,

    work_performed        TEXT         NOT NULL,
    observations          TEXT,
    parts_replaced        TEXT,
    recommendations       TEXT,
    -- Free-form JSON array of {label, ok, note} so the checklist can differ
    -- per service type without a migration every time ops change it.
    checklist_json        TEXT,

    panels_cleaned        INT,
    output_before_kw      NUMERIC(10, 2),
    output_after_kw       NUMERIC(10, 2),
    next_service_due      DATE,

    customer_signature_name VARCHAR(160),
    customer_remarks      TEXT,

    published             BOOLEAN      NOT NULL DEFAULT FALSE,
    published_at          TIMESTAMP,
    prepared_by           VARCHAR(120),
    created_at            TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP    NOT NULL DEFAULT now(),
    pdf_storage_key       VARCHAR(400),
    pdf_generated_at      TIMESTAMP
);

CREATE INDEX idx_service_reports_customer ON service_reports (customer_id, service_date DESC);

-- =========================================================
-- Support ticketing. A ticket is a conversation, so messages live in their
-- own append-only table rather than being squashed into a notes column.
-- =========================================================
CREATE TABLE support_tickets (
    id                 BIGSERIAL PRIMARY KEY,
    ticket_number      VARCHAR(32)  NOT NULL UNIQUE,
    customer_id        BIGINT       NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    booking_id         BIGINT       REFERENCES bookings(id) ON DELETE SET NULL,
    invoice_id         BIGINT       REFERENCES invoices(id) ON DELETE SET NULL,
    refund_id          BIGINT       REFERENCES refunds(id) ON DELETE SET NULL,

    category           VARCHAR(40)  NOT NULL,
    priority           VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    status             VARCHAR(24)  NOT NULL DEFAULT 'OPEN',
    subject            VARCHAR(200) NOT NULL,

    assigned_to        VARCHAR(120),
    -- When we promised a first reply, so a breach is measurable rather than felt.
    first_response_due TIMESTAMP,
    first_response_at  TIMESTAMP,
    resolved_at        TIMESTAMP,
    closed_at          TIMESTAMP,
    resolution_note    TEXT,

    last_message_at    TIMESTAMP    NOT NULL DEFAULT now(),
    unread_for_admin   BOOLEAN      NOT NULL DEFAULT TRUE,
    unread_for_customer BOOLEAN     NOT NULL DEFAULT FALSE,

    created_at         TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at         TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_support_tickets_customer ON support_tickets (customer_id, created_at DESC);
CREATE INDEX idx_support_tickets_status   ON support_tickets (status, priority, created_at);

CREATE TABLE support_messages (
    id              BIGSERIAL PRIMARY KEY,
    ticket_id       BIGINT      NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
    author_type     VARCHAR(16) NOT NULL,
    author_label    VARCHAR(120),
    body            TEXT        NOT NULL,
    -- Internal notes are visible to admins only and never leave the console.
    internal        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP   NOT NULL DEFAULT now()
);

CREATE INDEX idx_support_messages_ticket ON support_messages (ticket_id, created_at);
