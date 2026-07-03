package com.godoc.consult.doctor;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotent demo seeder: ensures every doctor has 30-minute slots (09:00–17:00 UTC,
 * weekdays) for the next {@value #DAYS_AHEAD} days. Runs on every startup; ON CONFLICT
 * DO NOTHING makes re-runs no-ops. A real system would replace this with doctor/admin
 * managed scheduling — called out as an explicit scope cut in the README.
 */
@Component
public class SlotSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SlotSeeder.class);

    private static final LocalTime FIRST_SLOT = LocalTime.of(9, 0);
    private static final LocalTime LAST_SLOT = LocalTime.of(16, 30);
    private static final int SLOT_MINUTES = 30;
    private static final int DAYS_AHEAD = 14;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final boolean enabled;

    public SlotSeeder(JdbcTemplate jdbc, Clock clock, @Value("${app.seed-slots:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        List<UUID> doctorIds = jdbc.queryForList("SELECT id FROM doctor", UUID.class);
        List<Object[]> rows = new ArrayList<>();
        LocalDate today = LocalDate.now(clock);
        for (UUID doctorId : doctorIds) {
            for (int day = 0; day < DAYS_AHEAD; day++) {
                LocalDate date = today.plusDays(day);
                if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    continue;
                }
                for (LocalTime time = FIRST_SLOT; !time.isAfter(LAST_SLOT); time = time.plusMinutes(SLOT_MINUTES)) {
                    OffsetDateTime start = date.atTime(time).atOffset(ZoneOffset.UTC);
                    rows.add(new Object[] {
                            UUID.randomUUID(), doctorId, start, start.plusMinutes(SLOT_MINUTES)});
                }
            }
        }
        int[] results = jdbc.batchUpdate(
                "INSERT INTO slot (id, doctor_id, start_at, end_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (doctor_id, start_at) DO NOTHING",
                rows);
        long inserted = Arrays.stream(results).filter(r -> r > 0).count();
        log.info("Slot seeder: ensured {} slots for {} doctors ({} newly inserted)",
                rows.size(), doctorIds.size(), inserted);
    }
}
