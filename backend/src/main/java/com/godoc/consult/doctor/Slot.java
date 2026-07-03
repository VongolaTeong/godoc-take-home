package com.godoc.consult.doctor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A bookable time window in a doctor's schedule. Deliberately has no "booked" flag:
 * availability is derived from the absence of an active booking (see
 * ux_active_booking_per_slot in V1__schema.sql), so there is a single source of truth.
 */
@Entity
@Table(name = "slot")
public class Slot {

    @Id
    private UUID id;

    @Column(name = "doctor_id", updatable = false)
    private UUID doctorId;

    @Column(name = "start_at", updatable = false)
    private Instant startAt;

    @Column(name = "end_at", updatable = false)
    private Instant endAt;

    protected Slot() {
    }

    public Slot(UUID id, UUID doctorId, Instant startAt, Instant endAt) {
        this.id = id;
        this.doctorId = doctorId;
        this.startAt = startAt;
        this.endAt = endAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDoctorId() {
        return doctorId;
    }

    public Instant getStartAt() {
        return startAt;
    }

    public Instant getEndAt() {
        return endAt;
    }
}
