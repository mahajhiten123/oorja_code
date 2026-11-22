-- =========================================================
-- V1: Initial schema for Oorja on Move
-- =========================================================

CREATE TABLE admins (
    id            BIGSERIAL PRIMARY KEY,
    name          VARCHAR(150) NOT NULL,
    email         VARCHAR(150) NOT NULL UNIQUE,
    phone         VARCHAR(20),
    role          VARCHAR(30)  NOT NULL DEFAULT 'SUPER_ADMIN',
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE customers (
    id             BIGSERIAL PRIMARY KEY,
    customer_code  VARCHAR(20)  NOT NULL UNIQUE,
    name           VARCHAR(150) NOT NULL,
    email          VARCHAR(150) NOT NULL UNIQUE,
    phone          VARCHAR(20),
    address        TEXT,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE plants (
    id             BIGSERIAL PRIMARY KEY,
    plant_code     VARCHAR(20)  NOT NULL UNIQUE,
    name           VARCHAR(150) NOT NULL,
    type           VARCHAR(20)  NOT NULL,
    capacity_kw    NUMERIC(10,2) NOT NULL,
    location       VARCHAR(255) NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    customer_id    BIGINT REFERENCES customers(id) ON DELETE SET NULL,
    created_at     TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE technicians (
    id                 BIGSERIAL PRIMARY KEY,
    technician_code    VARCHAR(20)  NOT NULL UNIQUE,
    name               VARCHAR(150) NOT NULL,
    phone              VARCHAR(20)  NOT NULL,
    email              VARCHAR(150),
    specialization     VARCHAR(150) NOT NULL,
    area               VARCHAR(150) NOT NULL,
    availability       VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    created_at         TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE bookings (
    id                BIGSERIAL PRIMARY KEY,
    token             VARCHAR(30)  NOT NULL UNIQUE,
    customer_id       BIGINT       NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
    plant_id          BIGINT       NOT NULL REFERENCES plants(id) ON DELETE CASCADE,
    technician_id     BIGINT       REFERENCES technicians(id) ON DELETE SET NULL,
    service_type      VARCHAR(150) NOT NULL,
    scheduled_date    DATE         NOT NULL,
    scheduled_time    TIME         NOT NULL,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    notes             TEXT,
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE TABLE otp_tokens (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(150) NOT NULL,
    otp_hash      VARCHAR(100) NOT NULL,
    purpose       VARCHAR(30)  NOT NULL,
    attempts      INT          NOT NULL DEFAULT 0,
    used          BOOLEAN      NOT NULL DEFAULT FALSE,
    expires_at    TIMESTAMP    NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_plants_customer_id       ON plants(customer_id);
CREATE INDEX idx_bookings_customer_id     ON bookings(customer_id);
CREATE INDEX idx_bookings_plant_id        ON bookings(plant_id);
CREATE INDEX idx_bookings_technician_id   ON bookings(technician_id);
CREATE INDEX idx_bookings_status          ON bookings(status);
CREATE INDEX idx_otp_email_purpose        ON otp_tokens(email, purpose);
CREATE INDEX idx_otp_expires_at           ON otp_tokens(expires_at);
