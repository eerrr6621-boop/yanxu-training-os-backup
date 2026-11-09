package com.training;

import java.time.*;
import java.util.*;

/**
 * Pure property check (no database): the narrowed occupation read in DispatchConfirmationGuard.bookings never drops a
 * row that could overlap the proposed window. For random proposals and random other bookings whose frozen windows carry
 * buffers up to the 10080-minute server cap, every overlapping or uncertain row passes relevantDate.
 */
public final class DispatchOccupationWindowTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }

    public static void main(String[] ignored) {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        Random random = new Random(20261108L);
        int overlapping = 0, excluded = 0;
        for (int round = 0; round < 20000; round++) {
            LocalDate day = LocalDate.of(2026, 1, 1).plusDays(random.nextInt(730));
            LocalTime start = LocalTime.of(random.nextInt(23), random.nextInt(4) * 15);
            Instant teachStart = day.atTime(start).atZone(zone).toInstant(), teachEnd = teachStart.plusSeconds(60L * (30 + random.nextInt(600)));
            Instant from = teachStart.minusSeconds(60L * random.nextInt(10081)), to = teachEnd.plusSeconds(60L * random.nextInt(10081));
            var proposed = new DispatchConfirmationGuard.Occupied(from, to, true, "");

            LocalDate otherDay = day.plusDays(random.nextInt(41) - 20);
            String otherDate = otherDay.toString();
            LocalTime otherStart = LocalTime.of(random.nextInt(23), random.nextInt(4) * 15);
            Instant otherTeachStart = otherDay.atTime(otherStart).atZone(zone).toInstant(), otherTeachEnd = otherTeachStart.plusSeconds(60L * (30 + random.nextInt(60)));
            var frozen = new DispatchConfirmationGuard.Occupied(otherTeachStart.minusSeconds(60L * random.nextInt(10081)), otherTeachEnd.plusSeconds(60L * random.nextInt(10081)), true, "");
            var dayLong = new DispatchConfirmationGuard.Occupied(otherDay.atStartOfDay(zone).toInstant(), otherDay.plusDays(1).atStartOfDay(zone).toInstant(), false, "");

            boolean mayOverlap = DispatchConfirmationGuard.overlap(proposed, frozen) || DispatchConfirmationGuard.overlap(proposed, dayLong);
            boolean read = DispatchConfirmationGuard.relevantDate(otherDate, from, to);
            if (mayOverlap) { overlapping++; check(read, "overlapping row must be read: " + otherDate + " vs " + from + ".." + to); }
            if (!read) excluded++;
        }
        check(overlapping > 1000 && excluded > 1000, "fixture exercises both overlapping and safely skipped rows: " + overlapping + "/" + excluded);
        Instant from = Instant.parse("2026-11-10T01:00:00Z"), to = Instant.parse("2026-11-10T03:00:00Z");
        for (String unknown : new String[]{"", "2026-02-30", "2026/11/10", "11-10", "2026-11-1"})
            check(DispatchConfirmationGuard.relevantDate(unknown, from, to), "unknown date is always read: " + unknown);
        check(!DispatchConfirmationGuard.relevantDate("2026-10-01", from, to) && !DispatchConfirmationGuard.relevantDate("2027-11-10", from, to), "far valid dates are skipped");
        check(DispatchConfirmationGuard.relevantDate("2026-11-02", from, to) && DispatchConfirmationGuard.relevantDate("2026-11-18", from, to), "dates within 8 days are read");
        check(!DispatchConfirmationGuard.relevantDate("2026-11-01", from, to) && !DispatchConfirmationGuard.relevantDate("2026-11-19", from, to), "dates beyond 8 days are skipped");
        System.out.println("DispatchOccupationWindowTest: " + checks + " checks passed; 20000 synthetic windows, no database.");
    }
}
