package com.training;

import java.time.*;

/**
 * Pure checks of the 5-year archive cutoff and of the time comparison used to pick archivable receipts (no database).
 * The archive copy/delete, replay from the archive and rollback run against H2 in RecommendationScaleHostTest.
 */
public final class DispatchConfirmationArchiveTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }

    public static void main(String[] ignored) {
        Instant now = Instant.parse("2031-11-06T02:00:00Z");
        Instant cutoff = DispatchConfirmationGuard.archiveCutoff(now);
        check(cutoff.equals(Instant.parse("2026-11-06T02:00:00Z")), "cutoff is exactly five calendar years earlier: " + cutoff);

        // confirmed_at is Instant.toString() text with varying fractional digits: compared as time, never as text.
        check(!DispatchConfirmationGuard.archivable("2026-11-06T02:00:00.500Z", cutoff), "half a second younger than five years stays (text order would archive it)");
        check(!DispatchConfirmationGuard.archivable("2026-11-06T02:00:00.000001Z", cutoff), "one microsecond younger stays");
        check(!DispatchConfirmationGuard.archivable("2026-11-06T02:00:00.000000001Z", cutoff), "one nanosecond younger stays");
        check(!DispatchConfirmationGuard.archivable("2026-11-06T02:00:00Z", cutoff), "exactly five years is not older than five years");
        check(DispatchConfirmationGuard.archivable("2026-11-06T01:59:59.999Z", cutoff), "one millisecond older is archived");
        check(DispatchConfirmationGuard.archivable("2026-11-06T01:59:59Z", cutoff), "whole second older is archived");
        Instant fractionalCutoff = Instant.parse("2026-11-06T02:00:00.500Z");
        check(DispatchConfirmationGuard.archivable("2026-11-06T02:00:00Z", fractionalCutoff), "whole-second record older than a fractional cutoff is archived (text order would keep it)");
        check(!DispatchConfirmationGuard.archivable("2026-11-06T02:00:00.5Z", fractionalCutoff), "same instant written with fewer digits is not older");

        // Unreadable or empty values are never archived.
        for (Object bad : new Object[]{null, "", "  ", "2026-11-06", "2026/11/06 02:00", "not a time", 12345L})
            check(!DispatchConfirmationGuard.archivable(bad, cutoff), "unreadable confirmation time is kept active: " + bad);

        // Calendar years in Asia/Shanghai, including a leap day.
        Instant leap = ZonedDateTime.of(2032, 2, 29, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant();
        check(DispatchConfirmationGuard.archiveCutoff(leap).equals(ZonedDateTime.of(2027, 2, 28, 12, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant()),
                "29 February minus five years is 28 February at the same Shanghai time");
        Instant shanghaiMidnight = ZonedDateTime.of(2031, 1, 1, 0, 30, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant();
        check(DispatchConfirmationGuard.archiveCutoff(shanghaiMidnight).equals(ZonedDateTime.of(2026, 1, 1, 0, 30, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant()),
                "year boundary follows Shanghai calendar, not UTC");

        check(DispatchConfirmationGuard.MAX_ACTIVE_REQUESTS == 20000 && DispatchConfirmationGuard.ARCHIVE_YEARS == 5, "capacity unchanged; archive age is the confirmed 5 years");
        check(DispatchConfirmationGuard.MAX_BUFFER_DAYS == 7, "occupation read window covers the 10080-minute (7-day) buffer cap");
        check(DispatchConfirmationGuard.calendarDate("2026-02-30") == null && DispatchConfirmationGuard.calendarDate("2026-11-1") == null
                && DispatchConfirmationGuard.calendarDate("2026-11-01").equals(LocalDate.of(2026, 11, 1)), "calendar date reading matches potentiallyRelevant");
        System.out.println("DispatchConfirmationArchiveTest: " + checks + " checks passed; synthetic only, no database.");
    }
}
