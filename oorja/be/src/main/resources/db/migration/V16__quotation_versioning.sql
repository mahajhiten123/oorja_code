-- =========================================================
-- V16: Quotation versioning + revision requests (Phase 7 / 8)
--
-- A quotation's financial values are never modified once sent. Instead every
-- price change is a new immutable row in quotation_versions; `quotations`
-- keeps a mirror of the current version's values (for cheap reads/filtering)
-- plus a pointer to whichever version, if any, the customer accepted.
--
-- Once a version is paid, it is permanently locked: nothing may ever write
-- to its financial fields again. This is what keeps the Razorpay-linked
-- financial history immutable end to end.
-- =========================================================

CREATE TABLE quotation_versions (
    id               BIGSERIAL PRIMARY KEY,
    quotation_id     BIGINT       NOT NULL REFERENCES quotations(id) ON DELETE CASCADE,
    version_number   INT          NOT NULL,

    subtotal         NUMERIC(12,2),
    discount_amount  NUMERIC(12,2) NOT NULL DEFAULT 0,
    tax_amount       NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_amount     NUMERIC(12,2),
    notes            TEXT,
    valid_until      DATE,

    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',

    -- Provenance: who authored this version. NULL created_by means the
    -- system (e.g. the empty v1 draft auto-created alongside the quotation).
    created_by       BIGINT,
    created_by_type  VARCHAR(16),

    sent_at          TIMESTAMP,
    viewed_at        TIMESTAMP,
    responded_at     TIMESTAMP,

    -- Set only once, the moment this exact version is paid for. After this,
    -- the version is permanently locked - see the application-level checks
    -- in QuotationService (getOrCreateEditableDraft refuses once any version
    -- of the quotation has been paid).
    payment_ref      VARCHAR(120),
    paid_at          TIMESTAMP,

    created_at       TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT uq_quotation_version_number UNIQUE (quotation_id, version_number),
    CONSTRAINT chk_quotation_version_status CHECK (
        status IN ('DRAFT', 'SENT', 'VIEWED', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'SUPERSEDED')
    ),
    CONSTRAINT chk_quotation_version_created_by_type CHECK (
        created_by_type IS NULL OR created_by_type IN ('ADMIN', 'CUSTOMER')
    )
);

CREATE INDEX idx_quotation_versions_quotation_id ON quotation_versions (quotation_id);

ALTER TABLE quotations
    ADD COLUMN current_version_number INT NOT NULL DEFAULT 1,
    ADD COLUMN accepted_version_id BIGINT REFERENCES quotation_versions(id);

-- Backfill: every existing quotation becomes v1, carrying over its current
-- financial/status fields exactly as they stand today.
INSERT INTO quotation_versions (
    quotation_id, version_number, subtotal, discount_amount, tax_amount, total_amount,
    notes, valid_until, status, created_by_type, sent_at, viewed_at, responded_at,
    payment_ref, paid_at, created_at
)
SELECT
    id, 1, subtotal, COALESCE(discount_amount, 0), COALESCE(tax_amount, 0), total_amount,
    notes, valid_until, status, 'ADMIN', sent_at, viewed_at, responded_at,
    payment_ref, CASE WHEN payment_ref IS NOT NULL THEN updated_at END, created_at
FROM quotations;

UPDATE quotations q
SET accepted_version_id = v.id
FROM quotation_versions v
WHERE v.quotation_id = q.id AND v.version_number = 1 AND q.status IN ('ACCEPTED', 'REJECTED');


CREATE TABLE quotation_revision_requests (
    id                  BIGSERIAL PRIMARY KEY,
    quotation_id        BIGINT      NOT NULL REFERENCES quotations(id) ON DELETE CASCADE,
    customer_id         BIGINT      NOT NULL REFERENCES customers(id),
    quotation_version_id BIGINT     NOT NULL REFERENCES quotation_versions(id),

    reason              VARCHAR(16) NOT NULL,
    message             TEXT        NOT NULL,

    status              VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    resolution_note     TEXT,

    created_at          TIMESTAMP   NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMP,
    resolved_by         BIGINT,

    CONSTRAINT chk_revision_request_reason CHECK (
        reason IN ('PRICE', 'SERVICE', 'QUANTITY', 'AMC', 'OTHER')
    ),
    CONSTRAINT chk_revision_request_status CHECK (
        status IN ('OPEN', 'IN_REVIEW', 'RESOLVED', 'REJECTED')
    )
);

CREATE INDEX idx_revision_requests_quotation_id ON quotation_revision_requests (quotation_id);
CREATE INDEX idx_revision_requests_open ON quotation_revision_requests (quotation_id) WHERE status IN ('OPEN', 'IN_REVIEW');
