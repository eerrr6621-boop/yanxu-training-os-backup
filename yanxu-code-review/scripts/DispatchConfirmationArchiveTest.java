package com.training;

import java.time.*;

/** Pure synthetic check of the 5-year archive cutoff (no database). The archive SQL itself needs an H2 run. */
public final class DispatchConfirmationArchiveTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }

    public static void main(String[] ignored) {
        Instant now = Instant.parse("2031-11-06T02:00:00Z");
        String cutoff = DispatchConfirmationGuard.archiveCutoff(now);
        check(cutoff.equals("2026-11-06T02:00:00Z"), "cutoff is exactly five calendar years earlier (Asia/Shanghai): " + cutoff);
        // dispatches.confirmed_at is written as Instant.toString(); plain string order must follow time order.
        check(Instant.parse("2026-11-06T01:59:59.999Z").toString().compareTo(cutoff) < 0, "just older than five years is archived");
        check(Instant.parse("2026-11-06T02:00:01Z").toString().compareTo(cutoff) > 0, "younger than five years stays active");
        check(Instant.parse("2027-01-01T00:00:00Z").toString().compareTo(cutoff) > 0, "recent receipts stay active");
        check(DispatchConfirmationGuard.MAX_ACTIVE_REQUESTS == 20000 && DispatchConfirmationGuard.ARCHIVE_YEARS == 5, "capacity unchanged; archive age is the confirmed 5 years");
        System.out.println("DispatchConfirmationArchiveTest: " + checks + " checks passed; synthetic only, no database.");
    }
}
