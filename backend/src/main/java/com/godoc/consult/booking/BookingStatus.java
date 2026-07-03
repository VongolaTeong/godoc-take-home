package com.godoc.consult.booking;

import java.util.Set;

public enum BookingStatus {
    PENDING, CONFIRMED, CANCELLED, COMPLETED;

    /**
     * Statuses that hold the slot. Must stay in sync with the WHERE clause of
     * ux_active_booking_per_slot (V1__schema.sql) — the DB index and this set are the
     * two halves of the same rule.
     */
    public static final Set<BookingStatus> ACTIVE = Set.of(PENDING, CONFIRMED);
}
