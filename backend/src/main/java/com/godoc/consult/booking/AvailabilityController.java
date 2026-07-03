package com.godoc.consult.booking;

import com.godoc.consult.doctor.Slot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public availability browsing. Lives in the booking package (despite the /doctors URL)
 * because "available" means "has no active booking" — a booking-side concern.
 * Defaults to the next 7 days; from/to are ISO-8601 instants, e.g. 2026-07-05T09:00:00Z.
 */
@RestController
@RequestMapping("/api/v1/doctors/{doctorId}/slots")
public class AvailabilityController {

    private final BookingService service;

    public AvailabilityController(BookingService service) {
        this.service = service;
    }

    public record SlotDto(UUID id, UUID doctorId, Instant startAt, Instant endAt) {

        static SlotDto from(Slot slot) {
            return new SlotDto(slot.getId(), slot.getDoctorId(), slot.getStartAt(), slot.getEndAt());
        }
    }

    @GetMapping
    public List<SlotDto> availableSlots(
            @PathVariable UUID doctorId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to) {
        return service.availableSlots(doctorId, from, to).stream()
                .map(SlotDto::from)
                .toList();
    }
}
