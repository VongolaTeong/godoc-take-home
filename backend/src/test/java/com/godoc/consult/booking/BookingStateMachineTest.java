package com.godoc.consult.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the booking state machine — no Spring, no database.
 * Every valid transition and every invalid one from each state.
 */
class BookingStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-01-01T09:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-01T09:05:00Z");

    private Booking pending() {
        return Booking.pendingFor(UUID.randomUUID(), UUID.randomUUID(), "key-1", T0);
    }

    private Booking confirmed() {
        Booking booking = pending();
        booking.confirm(T1);
        return booking;
    }

    @Test
    void newBookingStartsPending() {
        Booking booking = pending();
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.getCreatedAt()).isEqualTo(T0);
        assertThat(booking.getUpdatedAt()).isEqualTo(T0);
    }

    @Test
    void pendingCanBeConfirmed() {
        Booking booking = pending();
        booking.confirm(T1);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void pendingCanBeCancelled() {
        Booking booking = pending();
        booking.cancel(T1);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void confirmedCanBeCancelled() {
        Booking booking = confirmed();
        booking.cancel(T1);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void confirmedCanBeCompleted() {
        Booking booking = confirmed();
        booking.complete(T1);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
    }

    @Test
    void pendingCannotBeCompleted() {
        Booking booking = pending();
        assertThatThrownBy(() -> booking.complete(T1))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("complete")
                .hasMessageContaining("PENDING");
    }

    @Test
    void confirmedCannotBeConfirmedAgain() {
        Booking booking = confirmed();
        assertThatThrownBy(() -> booking.confirm(T1))
                .isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void cancelledIsTerminal() {
        Booking booking = pending();
        booking.cancel(T1);
        assertThatThrownBy(() -> booking.confirm(T1)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> booking.cancel(T1)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> booking.complete(T1)).isInstanceOf(InvalidTransitionException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void completedIsTerminal() {
        Booking booking = confirmed();
        booking.complete(T1);
        assertThatThrownBy(() -> booking.confirm(T1)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> booking.cancel(T1)).isInstanceOf(InvalidTransitionException.class);
        assertThatThrownBy(() -> booking.complete(T1)).isInstanceOf(InvalidTransitionException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.COMPLETED);
    }

    @Test
    void failedTransitionDoesNotChangeState() {
        Booking booking = pending();
        assertThatThrownBy(() -> booking.complete(T1)).isInstanceOf(InvalidTransitionException.class);
        assertThat(booking.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(booking.getUpdatedAt()).isEqualTo(T0);
    }
}
