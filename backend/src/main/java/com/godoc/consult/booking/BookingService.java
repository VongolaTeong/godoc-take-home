package com.godoc.consult.booking;

import com.godoc.consult.common.NotFoundException;
import com.godoc.consult.common.UnknownPatientException;
import com.godoc.consult.doctor.DoctorRepository;
import com.godoc.consult.doctor.Slot;
import com.godoc.consult.doctor.SlotRepository;
import com.godoc.consult.patient.PatientRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking use-cases.
 *
 * <p>{@link #book} is intentionally NOT wrapped in a service-level transaction: the INSERT
 * is the only write, and the database is the arbiter of conflicts (see
 * ux_active_booking_per_slot). Keeping the write in its own short transaction (the
 * repository call) lets us catch the constraint violation and translate or replay it —
 * inside a wider transaction, Postgres would refuse further statements after the error.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private static final Duration DEFAULT_AVAILABILITY_WINDOW = Duration.ofDays(7);
    private static final String ACTIVE_SLOT_CONSTRAINT = "ux_active_booking_per_slot";
    private static final String IDEMPOTENCY_CONSTRAINT = "uq_booking_patient_idem";

    private final BookingRepository bookings;
    private final SlotRepository slots;
    private final DoctorRepository doctors;
    private final PatientRepository patients;
    private final Clock clock;

    public BookingService(
            BookingRepository bookings,
            SlotRepository slots,
            DoctorRepository doctors,
            PatientRepository patients,
            Clock clock) {
        this.bookings = bookings;
        this.slots = slots;
        this.doctors = doctors;
        this.patients = patients;
        this.clock = clock;
    }

    /** created == false means the request was an idempotent replay of an earlier booking. */
    public record BookResult(Booking booking, boolean created) {
    }

    public BookResult book(UUID patientId, UUID slotId, String idempotencyKey) {
        requireKnownPatient(patientId);

        Optional<Booking> replayed = bookings.findByPatientIdAndIdempotencyKey(patientId, idempotencyKey);
        if (replayed.isPresent()) {
            return replay(replayed.get(), slotId);
        }

        Slot slot = slots.findById(slotId).orElseThrow(() -> new NotFoundException("Slot", slotId));
        if (!slot.getStartAt().isAfter(clock.instant())) {
            throw new SlotInPastException(slotId);
        }

        Booking booking = Booking.pendingFor(slotId, patientId, idempotencyKey, clock.instant());
        try {
            // saveAndFlush so a constraint violation surfaces here, already translated to
            // DataIntegrityViolationException, rather than at a later commit point.
            return new BookResult(bookings.saveAndFlush(booking), true);
        } catch (DataIntegrityViolationException e) {
            if (isViolationOf(e, IDEMPOTENCY_CONSTRAINT)) {
                // A concurrent duplicate of this exact request won the insert — replay it.
                Booking winner = bookings.findByPatientIdAndIdempotencyKey(patientId, idempotencyKey)
                        .orElseThrow(() -> e);
                return replay(winner, slotId);
            }
            if (isViolationOf(e, ACTIVE_SLOT_CONSTRAINT)) {
                // Rare interleaving: our own duplicate may have raced us in via the slot index
                // instead. Prefer replay semantics over a spurious conflict.
                Optional<Booking> winner = bookings.findByPatientIdAndIdempotencyKey(patientId, idempotencyKey);
                if (winner.isPresent()) {
                    return replay(winner.get(), slotId);
                }
                log.info("Slot {} contended: booking rejected by unique index", slotId);
                throw new SlotUnavailableException(slotId);
            }
            throw e;
        }
    }

    @Transactional
    public Booking confirm(UUID patientId, UUID bookingId) {
        return applyTransition(patientId, bookingId, b -> b.confirm(clock.instant()));
    }

    @Transactional
    public Booking cancel(UUID patientId, UUID bookingId) {
        return applyTransition(patientId, bookingId, b -> b.cancel(clock.instant()));
    }

    /** In a real system completion is a doctor/clinic action; authz is an explicit scope cut. */
    @Transactional
    public Booking complete(UUID patientId, UUID bookingId) {
        return applyTransition(patientId, bookingId, b -> b.complete(clock.instant()));
    }

    public List<BookingView> listFor(UUID patientId) {
        requireKnownPatient(patientId);
        return bookings.findViewsForPatient(patientId);
    }

    public BookingView getFor(UUID patientId, UUID bookingId) {
        requireKnownPatient(patientId);
        return bookings.findViewForPatient(bookingId, patientId)
                .orElseThrow(() -> new NotFoundException("Booking", bookingId));
    }

    public List<Slot> availableSlots(UUID doctorId, Instant from, Instant to) {
        if (!doctors.existsById(doctorId)) {
            throw new NotFoundException("Doctor", doctorId);
        }
        Instant effectiveFrom = from != null ? from : clock.instant();
        Instant effectiveTo = to != null ? to : effectiveFrom.plus(DEFAULT_AVAILABILITY_WINDOW);
        if (!effectiveTo.isAfter(effectiveFrom)) {
            throw new IllegalArgumentException("'to' must be after 'from'");
        }
        return bookings.findAvailableSlots(doctorId, effectiveFrom, effectiveTo, BookingStatus.ACTIVE);
    }

    private BookResult replay(Booking existing, UUID requestedSlotId) {
        if (!existing.getSlotId().equals(requestedSlotId)) {
            throw new IdempotencyKeyMismatchException();
        }
        return new BookResult(existing, false);
    }

    private Booking applyTransition(UUID patientId, UUID bookingId, Consumer<Booking> action) {
        requireKnownPatient(patientId);
        Booking booking = bookings.findById(bookingId)
                // Scoped to the owner; 404 (not 403) so we don't leak other patients' booking ids.
                .filter(b -> b.getPatientId().equals(patientId))
                .orElseThrow(() -> new NotFoundException("Booking", bookingId));
        action.accept(booking);
        // Flush now so a lost optimistic-lock race surfaces as
        // ObjectOptimisticLockingFailureException from this method, not at commit.
        bookings.flush();
        return booking;
    }

    private void requireKnownPatient(UUID patientId) {
        if (!patients.existsById(patientId)) {
            throw new UnknownPatientException(patientId);
        }
    }

    private static boolean isViolationOf(DataIntegrityViolationException e, String constraintName) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                String name = cve.getConstraintName();
                return name != null && name.toLowerCase().contains(constraintName);
            }
        }
        return false;
    }
}
