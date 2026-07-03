package com.godoc.consult.booking;

import com.godoc.consult.doctor.Slot;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    Optional<Booking> findByPatientIdAndIdempotencyKey(UUID patientId, String idempotencyKey);

    long countBySlotIdAndStatusIn(UUID slotId, Collection<BookingStatus> statuses);

    /**
     * Slots of a doctor in [from, to) with no active booking — the query-side twin of
     * ux_active_booking_per_slot. Lives here (not in SlotRepository) because availability
     * is a booking concern; the doctor package knows nothing about bookings.
     */
    @Query("""
            select s from Slot s
            where s.doctorId = :doctorId
              and s.startAt >= :from and s.startAt < :to
              and not exists (
                  select b.id from Booking b
                  where b.slotId = s.id and b.status in :activeStatuses)
            order by s.startAt
            """)
    List<Slot> findAvailableSlots(
            @Param("doctorId") UUID doctorId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("activeStatuses") Collection<BookingStatus> activeStatuses);

    @Query("""
            select new com.godoc.consult.booking.BookingView(
                b.id, b.status, s.id, s.startAt, s.endAt, d.id, d.name, b.createdAt)
            from Booking b
            join Slot s on s.id = b.slotId
            join Doctor d on d.id = s.doctorId
            where b.patientId = :patientId
            order by s.startAt
            """)
    List<BookingView> findViewsForPatient(@Param("patientId") UUID patientId);

    @Query("""
            select new com.godoc.consult.booking.BookingView(
                b.id, b.status, s.id, s.startAt, s.endAt, d.id, d.name, b.createdAt)
            from Booking b
            join Slot s on s.id = b.slotId
            join Doctor d on d.id = s.doctorId
            where b.id = :bookingId and b.patientId = :patientId
            """)
    Optional<BookingView> findViewForPatient(
            @Param("bookingId") UUID bookingId,
            @Param("patientId") UUID patientId);
}
