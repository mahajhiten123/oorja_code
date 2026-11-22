-- =========================================================
-- V4: Booking attachments (site photos / documents uploaded
-- by customers when requesting installation quotes, etc.)
-- =========================================================

CREATE TABLE booking_attachments (
    id             BIGSERIAL PRIMARY KEY,
    booking_id     BIGINT       NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    file_name      VARCHAR(255) NOT NULL,
    content_type   VARCHAR(150),
    size_bytes     BIGINT,
    storage_path   VARCHAR(500) NOT NULL,
    uploaded_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_booking_attachments_booking_id ON booking_attachments(booking_id);
