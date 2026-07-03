package com.godoc.consult;

import com.fasterxml.jackson.databind.JsonNode;
import com.godoc.consult.booking.BookingRepository;
import com.godoc.consult.doctor.Doctor;
import com.godoc.consult.doctor.DoctorRepository;
import com.godoc.consult.doctor.Slot;
import com.godoc.consult.doctor.SlotRepository;
import com.godoc.consult.patient.Patient;
import com.godoc.consult.patient.PatientRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Base for integration tests: boots the full app on a random port against a real
 * Postgres 16 (Testcontainers). All subclasses share one application context and one
 * container. Tests create their own doctors/patients/slots (random UUIDs) so they are
 * isolated from each other and from the demo seed data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected DoctorRepository doctorRepository;

    @Autowired
    protected SlotRepository slotRepository;

    @Autowired
    protected PatientRepository patientRepository;

    @Autowired
    protected BookingRepository bookingRepository;

    @Autowired
    protected Clock clock;

    protected UUID createDoctor() {
        UUID id = UUID.randomUUID();
        doctorRepository.save(new Doctor(id, "Dr. Test " + shortId(id), "Testing"));
        return id;
    }

    protected UUID createPatient() {
        UUID id = UUID.randomUUID();
        patientRepository.save(new Patient(id, "Patient " + shortId(id), "patient-" + id + "@example.com"));
        return id;
    }

    protected UUID createSlot(UUID doctorId, Instant startAt) {
        UUID id = UUID.randomUUID();
        slotRepository.save(new Slot(id, doctorId, startAt, startAt.plus(Duration.ofMinutes(30))));
        return id;
    }

    /** A slot start time safely in the future: tomorrow (UTC) at the given hour/minute. */
    protected Instant tomorrowAt(int hour, int minute) {
        return LocalDate.now(clock).plusDays(1).atTime(hour, minute).toInstant(ZoneOffset.UTC);
    }

    protected HttpHeaders patientHeaders(UUID patientId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Patient-Id", patientId.toString());
        return headers;
    }

    protected ResponseEntity<JsonNode> postBooking(UUID patientId, UUID slotId, String idempotencyKey) {
        HttpHeaders headers = patientHeaders(patientId);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/v1/bookings", HttpMethod.POST,
                new HttpEntity<>(Map.of("slotId", slotId.toString()), headers), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> postAction(UUID patientId, UUID bookingId, String action) {
        return rest.exchange("/api/v1/bookings/" + bookingId + "/" + action, HttpMethod.POST,
                new HttpEntity<>(patientHeaders(patientId)), JsonNode.class);
    }

    protected ResponseEntity<JsonNode> getBooking(UUID patientId, UUID bookingId) {
        return rest.exchange("/api/v1/bookings/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(patientHeaders(patientId)), JsonNode.class);
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }
}
