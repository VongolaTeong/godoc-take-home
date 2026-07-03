package com.godoc.consult.booking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Booking endpoints. The caller identifies as a patient via the X-Patient-Id header
 * (documented auth stub). POST /bookings requires an Idempotency-Key so client retries
 * can never create duplicates.
 */
@RestController
@RequestMapping("/api/v1/bookings")
@Validated
public class BookingController {

    private final BookingService service;

    public BookingController(BookingService service) {
        this.service = service;
    }

    public record BookRequest(@NotNull UUID slotId) {
    }

    @PostMapping
    public ResponseEntity<BookingDto> book(
            @RequestHeader("X-Patient-Id") UUID patientId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody BookRequest request) {
        BookingService.BookResult result = service.book(patientId, request.slotId(), idempotencyKey);
        BookingDto dto = BookingDto.from(result.booking());
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/bookings/" + dto.id())).body(dto)
                : ResponseEntity.ok(dto); // idempotent replay of an earlier request
    }

    @GetMapping
    public List<BookingView> myBookings(@RequestHeader("X-Patient-Id") UUID patientId) {
        return service.listFor(patientId);
    }

    @GetMapping("/{bookingId}")
    public BookingView get(
            @RequestHeader("X-Patient-Id") UUID patientId,
            @PathVariable UUID bookingId) {
        return service.getFor(patientId, bookingId);
    }

    @PostMapping("/{bookingId}/confirm")
    public BookingDto confirm(
            @RequestHeader("X-Patient-Id") UUID patientId,
            @PathVariable UUID bookingId) {
        return BookingDto.from(service.confirm(patientId, bookingId));
    }

    @PostMapping("/{bookingId}/cancel")
    public BookingDto cancel(
            @RequestHeader("X-Patient-Id") UUID patientId,
            @PathVariable UUID bookingId) {
        return BookingDto.from(service.cancel(patientId, bookingId));
    }

    @PostMapping("/{bookingId}/complete")
    public BookingDto complete(
            @RequestHeader("X-Patient-Id") UUID patientId,
            @PathVariable UUID bookingId) {
        return BookingDto.from(service.complete(patientId, bookingId));
    }
}
