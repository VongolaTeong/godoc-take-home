package com.godoc.consult.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Booking aggregate root. All state changes go through the transition methods below —
 * there is deliberately no status setter, so the state machine cannot be bypassed.
 *
 * Valid transitions:
 * <pre>
 *   (new) → PENDING → CONFIRMED → COMPLETED
 *              │           │
 *              └───────────┴──→ CANCELLED
 * </pre>
 *
 * Concurrency: {@code @Version} optimistic locking guards racing transitions (e.g. two
 * confirms); the partial unique index ux_active_booking_per_slot guards racing inserts.
 */
@Entity
@Table(name = "booking")
public class Booking {

    @Id
    private UUID id;

    @Column(name = "slot_id", updatable = false)
    private UUID slotId;

    @Column(name = "patient_id", updatable = false)
    private UUID patientId;

    @Enumerated(EnumType.STRING)
    private BookingStatus status;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    /** Wrapper type on purpose: version == null tells Spring Data the entity is new → INSERT. */
    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Booking() {
    }

    public static Booking pendingFor(UUID slotId, UUID patientId, String idempotencyKey, Instant now) {
        Booking booking = new Booking();
        booking.id = UUID.randomUUID();
        booking.slotId = slotId;
        booking.patientId = patientId;
        booking.idempotencyKey = idempotencyKey;
        booking.status = BookingStatus.PENDING;
        booking.createdAt = now;
        booking.updatedAt = now;
        return booking;
    }

    public void confirm(Instant now) {
        transition("confirm", Set.of(BookingStatus.PENDING), BookingStatus.CONFIRMED, now);
    }

    public void cancel(Instant now) {
        transition("cancel", BookingStatus.ACTIVE, BookingStatus.CANCELLED, now);
    }

    public void complete(Instant now) {
        transition("complete", Set.of(BookingStatus.CONFIRMED), BookingStatus.COMPLETED, now);
    }

    private void transition(String action, Set<BookingStatus> allowedFrom, BookingStatus to, Instant now) {
        if (!allowedFrom.contains(status)) {
            throw new InvalidTransitionException(action, status);
        }
        this.status = to;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSlotId() {
        return slotId;
    }

    public UUID getPatientId() {
        return patientId;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
