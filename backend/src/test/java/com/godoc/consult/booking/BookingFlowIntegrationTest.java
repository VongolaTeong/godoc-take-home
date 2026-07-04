package com.godoc.consult.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.godoc.consult.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end booking flow over real HTTP against real Postgres: happy path,
 * sequential double-booking, idempotent replay, cancellation freeing the slot,
 * and the error contract (404/401/400/422/409 as problem+json).
 */
class BookingFlowIntegrationTest extends AbstractIntegrationTest {

    private UUID doctorId;
    private UUID patientA;
    private UUID patientB;

    @BeforeEach
    void setUp() {
        doctorId = createDoctor();
        patientA = createPatient();
        patientB = createPatient();
    }

    @Test
    void bookingASlotSucceedsAndRemovesItFromAvailability() {
        UUID slotId = createSlot(doctorId, tomorrowAt(9, 0));
        assertThat(availableSlotIds()).contains(slotId.toString());

        ResponseEntity<JsonNode> response = postBooking(patientA, slotId, UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();
        JsonNode body = response.getBody();
        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("slotId").asText()).isEqualTo(slotId.toString());
        assertThat(availableSlotIds()).doesNotContain(slotId.toString());
    }

    @Test
    void bookingAnAlreadyBookedSlotConflicts() {
        UUID slotId = createSlot(doctorId, tomorrowAt(9, 30));
        assertThat(postBooking(patientA, slotId, UUID.randomUUID().toString()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> second = postBooking(patientB, slotId, UUID.randomUUID().toString());

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("title").asText()).isEqualTo("Slot already booked");
    }

    @Test
    void replayingTheSameIdempotencyKeyReturnsTheOriginalBooking() {
        UUID slotId = createSlot(doctorId, tomorrowAt(10, 0));
        String key = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> first = postBooking(patientA, slotId, key);
        ResponseEntity<JsonNode> replay = postBooking(patientA, slotId, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().get("id").asText()).isEqualTo(first.getBody().get("id").asText());
        assertThat(bookingRepository.countBySlotIdAndStatusIn(slotId, BookingStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    void reusingAnIdempotencyKeyForADifferentSlotIsRejected() {
        UUID slotOne = createSlot(doctorId, tomorrowAt(10, 30));
        UUID slotTwo = createSlot(doctorId, tomorrowAt(11, 0));
        String key = UUID.randomUUID().toString();
        assertThat(postBooking(patientA, slotOne, key).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> misuse = postBooking(patientA, slotTwo, key);

        assertThat(misuse.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(misuse.getBody().get("title").asText()).isEqualTo("Idempotency key reuse");
    }

    @Test
    void cancellingABookingFreesTheSlotForRebooking() {
        UUID slotId = createSlot(doctorId, tomorrowAt(11, 30));
        ResponseEntity<JsonNode> booked = postBooking(patientA, slotId, UUID.randomUUID().toString());
        UUID bookingId = UUID.fromString(booked.getBody().get("id").asText());

        ResponseEntity<JsonNode> cancelled = postAction(patientA, bookingId, "cancel");
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody().get("status").asText()).isEqualTo("CANCELLED");

        // The partial unique index only covers active bookings, so the slot is free again.
        assertThat(availableSlotIds()).contains(slotId.toString());
        assertThat(postBooking(patientB, slotId, UUID.randomUUID().toString()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void confirmThenCompleteHappyPath() {
        UUID slotId = createSlot(doctorId, tomorrowAt(12, 0));
        UUID bookingId = UUID.fromString(
                postBooking(patientA, slotId, UUID.randomUUID().toString()).getBody().get("id").asText());

        assertThat(postAction(patientA, bookingId, "confirm").getBody().get("status").asText())
                .isEqualTo("CONFIRMED");
        assertThat(postAction(patientA, bookingId, "complete").getBody().get("status").asText())
                .isEqualTo("COMPLETED");
    }

    @Test
    void cancelledBookingCannotBeConfirmed() {
        UUID slotId = createSlot(doctorId, tomorrowAt(12, 30));
        UUID bookingId = UUID.fromString(
                postBooking(patientA, slotId, UUID.randomUUID().toString()).getBody().get("id").asText());
        postAction(patientA, bookingId, "cancel");

        ResponseEntity<JsonNode> confirm = postAction(patientA, bookingId, "confirm");

        assertThat(confirm.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(confirm.getBody().get("title").asText()).isEqualTo("Invalid state transition");
    }

    @Test
    void bookingASlotInThePastIsRejected() {
        UUID pastSlot = createSlot(doctorId, clock.instant().minusSeconds(3600));

        ResponseEntity<JsonNode> response = postBooking(patientA, pastSlot, UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("title").asText()).isEqualTo("Slot no longer bookable");
    }

    @Test
    void bookingAnUnknownSlotIs404() {
        assertThat(postBooking(patientA, UUID.randomUUID(), UUID.randomUUID().toString()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void unknownPatientIs401() {
        UUID slotId = createSlot(doctorId, tomorrowAt(13, 0));
        assertThat(postBooking(UUID.randomUUID(), slotId, UUID.randomUUID().toString()).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void anotherPatientsBookingIsInvisibleAndUntouchable() {
        UUID slotId = createSlot(doctorId, tomorrowAt(13, 30));
        UUID bookingId = UUID.fromString(
                postBooking(patientA, slotId, UUID.randomUUID().toString()).getBody().get("id").asText());

        assertThat(getBooking(patientB, bookingId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(postAction(patientB, bookingId, "cancel").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void missingIdempotencyKeyIs400AndMissingPatientHeaderIs401() {
        UUID slotId = createSlot(doctorId, tomorrowAt(14, 0));

        var noKeyHeaders = patientHeaders(patientA);
        noKeyHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> noKey = rest.exchange("/api/v1/bookings",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(java.util.Map.of("slotId", slotId.toString()), noKeyHeaders),
                JsonNode.class);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        var noPatientHeaders = new org.springframework.http.HttpHeaders();
        noPatientHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        noPatientHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> noPatient = rest.exchange("/api/v1/bookings",
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(java.util.Map.of("slotId", slotId.toString()), noPatientHeaders),
                JsonNode.class);
        assertThat(noPatient.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void myBookingsListsBookingsWithSlotAndDoctorContext() {
        UUID slotId = createSlot(doctorId, tomorrowAt(14, 30));
        postBooking(patientA, slotId, UUID.randomUUID().toString());

        ResponseEntity<JsonNode> response = rest.exchange("/api/v1/bookings",
                org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(patientHeaders(patientA)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode mine = response.getBody();
        assertThat(mine.isArray()).isTrue();
        assertThat(mine.size()).isEqualTo(1);
        assertThat(mine.get(0).get("slotId").asText()).isEqualTo(slotId.toString());
        assertThat(mine.get(0).get("doctorName").asText()).startsWith("Dr. Test");
    }

    @Test
    void corsPreflightAllowsTheConfiguredUiOrigin() {
        // Origin configured in AbstractIntegrationTest; mirrors the Cloudflare Pages setup.
        var headers = new org.springframework.http.HttpHeaders();
        headers.setOrigin("https://ui.example.test");
        headers.setAccessControlRequestMethod(org.springframework.http.HttpMethod.POST);
        headers.setAccessControlRequestHeaders(
                List.of("Content-Type", "X-Patient-Id", "Idempotency-Key"));

        ResponseEntity<Void> preflight = rest.exchange("/api/v1/bookings",
                org.springframework.http.HttpMethod.OPTIONS,
                new org.springframework.http.HttpEntity<>(headers), Void.class);

        assertThat(preflight.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(preflight.getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("https://ui.example.test");
        assertThat(preflight.getHeaders().getAccessControlAllowHeaders())
                .contains("Content-Type", "X-Patient-Id", "Idempotency-Key");
    }

    private List<String> availableSlotIds() {
        ResponseEntity<JsonNode> response =
                rest.getForEntity("/api/v1/doctors/" + doctorId + "/slots", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> ids = new ArrayList<>();
        response.getBody().forEach(slot -> ids.add(slot.get("id").asText()));
        return ids;
    }
}
