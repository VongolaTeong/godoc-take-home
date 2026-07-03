package com.godoc.consult.booking;

import java.time.Instant;
import java.util.UUID;

public record BookingDto(UUID id, UUID slotId, UUID patientId, BookingStatus status, Instant createdAt) {

    public static BookingDto from(Booking booking) {
        return new BookingDto(
                booking.getId(),
                booking.getSlotId(),
                booking.getPatientId(),
                booking.getStatus(),
                booking.getCreatedAt());
    }
}
