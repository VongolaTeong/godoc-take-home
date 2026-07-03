package com.godoc.consult.booking;

import java.time.Instant;
import java.util.UUID;

/** Read model for listing/inspecting bookings: booking + its slot and doctor context. */
public record BookingView(
        UUID id,
        BookingStatus status,
        UUID slotId,
        Instant startAt,
        Instant endAt,
        UUID doctorId,
        String doctorName,
        Instant createdAt) {
}
