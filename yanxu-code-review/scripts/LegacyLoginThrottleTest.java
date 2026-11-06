package com.training;

import java.util.concurrent.atomic.AtomicLong;

/** Pure synthetic checks for the legacy-mode failed-login throttle: no database, listener or network. */
public final class LegacyLoginThrottleTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static boolean blocked(LegacyLoginThrottle t, String user, String source) {
        try { t.check(user, source); return false; }
        catch (Api.ApiException e) { check(e.code == 429, "throttle answers 429"); return true; }
    }

    public static void main(String[] ignored) {
        AtomicLong now = new AtomicLong(1_000_000L);
        LegacyLoginThrottle t = new LegacyLoginThrottle(now::get);

        // Account limit: the 10th failure blocks the 11th attempt; other accounts are unaffected.
        for (int i = 0; i < LegacyLoginThrottle.MAX_ACCOUNT_FAILURES - 1; i++) { check(!blocked(t, "synthetic-a", "198.51.100.1"), "below account limit"); t.failure("synthetic-a", "198.51.100.1"); }
        check(!blocked(t, "synthetic-a", "198.51.100.1"), "9 failures still allowed");
        t.failure("synthetic-a", "198.51.100.1");
        check(blocked(t, "synthetic-a", "198.51.100.1"), "10 failures block the account");
        check(blocked(t, "synthetic-a", "203.0.113.9"), "account block applies from another source");
        check(!blocked(t, "synthetic-b", "198.51.100.1"), "other account on same source not blocked");

        // Window: failures older than 15 minutes no longer count.
        now.addAndGet(LegacyLoginThrottle.WINDOW_MILLIS);
        check(!blocked(t, "synthetic-a", "198.51.100.1"), "block expires after the window");

        // Success clears the account counter but not the source counter.
        for (int i = 0; i < 5; i++) t.failure("synthetic-c", "198.51.100.2");
        t.success("synthetic-c");
        for (int i = 0; i < LegacyLoginThrottle.MAX_ACCOUNT_FAILURES - 1; i++) t.failure("synthetic-c", "198.51.100.2");
        check(!blocked(t, "synthetic-c", "198.51.100.2"), "success reset the account count");

        // Source limit: many different account names from one source.
        LegacyLoginThrottle s = new LegacyLoginThrottle(now::get);
        for (int i = 0; i < LegacyLoginThrottle.MAX_SOURCE_FAILURES; i++) s.failure("spray-" + i, "198.51.100.3");
        check(blocked(s, "fresh-name", "198.51.100.3"), "source limit blocks name spraying");
        check(!blocked(s, "fresh-name", "198.51.100.4"), "different source unaffected");
        check(!blocked(s, "fresh-name", null), "unknown source falls back to account-only checks");

        // Memory stays bounded.
        LegacyLoginThrottle m = new LegacyLoginThrottle(now::get);
        for (int i = 0; i < LegacyLoginThrottle.MAX_KEYS + 500; i++) m.failure("flood-" + i, null);
        check(m.trackedKeys() <= LegacyLoginThrottle.MAX_KEYS, "tracked keys bounded");

        System.out.println("LegacyLoginThrottleTest: " + checks + " checks passed; synthetic only, no database/listener/network.");
    }
}
