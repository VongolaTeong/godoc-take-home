-- Core schema.
--
-- Design note: slot availability is DERIVED, not stored. A slot is available iff it has
-- no active booking. There is deliberately no `booked` flag on slot — that would be a
-- second source of truth that could drift from the bookings table.
--
-- The partial unique index ux_active_booking_per_slot is the concurrency arbiter:
-- no matter how many app instances race, two active bookings for the same slot
-- cannot both commit.

CREATE TABLE doctor (
    id         UUID PRIMARY KEY,
    name       TEXT        NOT NULL,
    specialty  TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE patient (
    id         UUID PRIMARY KEY,
    name       TEXT        NOT NULL,
    email      TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_patient_email UNIQUE (email)
);

CREATE TABLE slot (
    id         UUID PRIMARY KEY,
    doctor_id  UUID        NOT NULL REFERENCES doctor (id),
    start_at   TIMESTAMPTZ NOT NULL,
    end_at     TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Doubles as the index for "slots of doctor X ordered by time" queries.
    CONSTRAINT uq_slot_doctor_start UNIQUE (doctor_id, start_at),
    CONSTRAINT ck_slot_times CHECK (end_at > start_at)
);

CREATE TABLE booking (
    id              UUID PRIMARY KEY,
    slot_id         UUID        NOT NULL REFERENCES slot (id),
    patient_id      UUID        NOT NULL REFERENCES patient (id),
    status          TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL,
    version         BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_booking_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'COMPLETED')),
    -- Idempotency: one booking attempt per (patient, Idempotency-Key).
    CONSTRAINT uq_booking_patient_idem UNIQUE (patient_id, idempotency_key)
);

-- THE double-booking defense: at most one active booking per slot, enforced by Postgres.
-- CANCELLED/COMPLETED bookings fall outside the predicate, so cancelling frees the slot
-- for rebooking with no extra bookkeeping.
CREATE UNIQUE INDEX ux_active_booking_per_slot
    ON booking (slot_id)
    WHERE status IN ('PENDING', 'CONFIRMED');

CREATE INDEX ix_booking_patient ON booking (patient_id);
