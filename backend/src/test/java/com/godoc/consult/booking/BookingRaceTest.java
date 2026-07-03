package com.godoc.consult.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.godoc.consult.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * The core correctness guarantee of this service, exercised over real HTTP against real
 * Postgres: no interleaving of concurrent requests may ever produce two active bookings
 * for one slot, and no interleaving of concurrent transitions may apply twice.
 */
class BookingRaceTest extends AbstractIntegrationTest {

    private static final int CONTENDERS = 50;

    @Test
    void fiftyConcurrentRequestsForOneSlotProduceExactlyOneBooking() throws Exception {
        UUID doctorId = createDoctor();
        UUID slotId = createSlot(doctorId, tomorrowAt(9, 0));
        List<UUID> patientIds = new ArrayList<>();
        for (int i = 0; i < CONTENDERS; i++) {
            patientIds.add(createPatient());
        }

        List<Integer> statuses = runConcurrently(patientIds.stream()
                .map(patientId -> (Callable<Integer>) () ->
                        postBooking(patientId, slotId, UUID.randomUUID().toString())
                                .getStatusCode().value())
                .toList());

        assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
        assertThat(statuses).filteredOn(status -> status == 409).hasSize(CONTENDERS - 1);
        // The database agrees: exactly one active booking exists for the slot.
        assertThat(bookingRepository.countBySlotIdAndStatusIn(slotId, BookingStatus.ACTIVE)).isEqualTo(1);
    }

    @Test
    void concurrentConfirmsOfOneBookingHaveExactlyOneWinner() throws Exception {
        UUID doctorId = createDoctor();
        UUID slotId = createSlot(doctorId, tomorrowAt(10, 0));
        UUID patientId = createPatient();
        UUID bookingId = UUID.fromString(
                postBooking(patientId, slotId, UUID.randomUUID().toString())
                        .getBody().get("id").asText());

        List<Integer> statuses = runConcurrently(List.of(
                () -> postAction(patientId, bookingId, "confirm").getStatusCode().value(),
                () -> postAction(patientId, bookingId, "confirm").getStatusCode().value()));

        // The loser fails either on the optimistic lock or on the already-CONFIRMED state —
        // both are 409. Exactly one request may win.
        assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
        assertThat(statuses).filteredOn(status -> status == 409).hasSize(1);
        assertThat(getBooking(patientId, bookingId).getBody().get("status").asText())
                .isEqualTo("CONFIRMED");
    }

    /** Runs all tasks as simultaneously as a latch can make them and returns their results. */
    private List<Integer> runConcurrently(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(30, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
