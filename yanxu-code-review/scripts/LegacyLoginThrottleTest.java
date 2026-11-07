package com.training;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Pure synthetic checks for the legacy-mode failed-login throttle: no database, listener or network.
 * The concurrency part reproduces LoginVerificationHost's protocol (check, then check again under the business lock
 * before the password check, record inside the lock) with a deterministic barrier.
 */
public final class LegacyLoginThrottleTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static String refusal(LegacyLoginThrottle t, String user, String source) {
        try { t.check(user, source); return null; }
        catch (Api.ApiException e) { check(e.code == 429, "throttle answers 429"); return e.getMessage(); }
    }
    private static boolean blocked(LegacyLoginThrottle t, String user, String source) { return refusal(t, user, source) != null; }

    public static void main(String[] ignored) throws Exception {
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

        // Unidentified sources share one limit: changing names does not bypass it.
        LegacyLoginThrottle u = new LegacyLoginThrottle(now::get);
        for (int i = 0; i < LegacyLoginThrottle.MAX_SOURCE_FAILURES; i++) u.failure("unknown-spray-" + i, null);
        check(blocked(u, "another-fresh-name", null), "unknown-source spraying is limited like one source");
        check(!blocked(u, "another-fresh-name", "198.51.100.5"), "identified sources are not affected by the shared unknown key");

        // Capacity: a flood of new keys never displaces an active block.
        LegacyLoginThrottle c = new LegacyLoginThrottle(now::get);
        for (int i = 0; i < LegacyLoginThrottle.MAX_ACCOUNT_FAILURES; i++) c.failure("victim-blocked", "203.0.113.50");
        for (int i = 0; i < LegacyLoginThrottle.MAX_KEYS * 2; i++) { if (!blocked(c, "flood-" + i, "flood-src-" + i)) c.failure("flood-" + i, "flood-src-" + i); }
        check(c.trackedKeys() <= LegacyLoginThrottle.MAX_KEYS, "tracked keys bounded");
        check(blocked(c, "victim-blocked", "203.0.113.51"), "blocked account still blocked after 20000 new keys");

        // Capacity: when every tracked key is an active block, new keys are refused instead of evicting a block.
        LegacyLoginThrottle full = new LegacyLoginThrottle(now::get);
        int accounts = 0;
        while (full.trackedKeys() < LegacyLoginThrottle.MAX_KEYS) {
            String name = "full-" + accounts++;
            for (int i = 0; i < LegacyLoginThrottle.MAX_ACCOUNT_FAILURES; i++) full.failure(name, null);
        }
        check(full.trackedKeys() == LegacyLoginThrottle.MAX_KEYS, "table filled with active blocks");
        String saturated = refusal(full, "never-seen", "198.51.100.77");
        check(LegacyLoginThrottle.SATURATED.equals(saturated), "new key refused while table is full of blocks: " + saturated);
        check(blocked(full, "full-0", "198.51.100.78"), "oldest block was not evicted");
        now.addAndGet(LegacyLoginThrottle.WINDOW_MILLIS);
        check(!blocked(full, "never-seen", "198.51.100.77"), "after the window, entries expire and new attempts are allowed");

        // Concurrency: 9 prior failures, two simultaneous wrong-password attempts. Only one may reach the password check.
        for (int round = 0; round < 50; round++) {
            LegacyLoginThrottle r = new LegacyLoginThrottle(now::get);
            for (int i = 0; i < LegacyLoginThrottle.MAX_ACCOUNT_FAILURES - 1; i++) r.failure("race", "198.51.100.9");
            Object lock = new Object();
            CyclicBarrier bothPassedFirstCheck = new CyclicBarrier(2);
            AtomicInteger passwordChecks = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            List<Future<Integer>> attempts = new ArrayList<>();
            for (int k = 0; k < 2; k++) attempts.add(pool.submit(() -> {
                try { r.check("race", "198.51.100.9"); } catch (Api.ApiException e) { return e.code; }
                bothPassedFirstCheck.await(5, TimeUnit.SECONDS);          // both are past the early check
                synchronized (lock) {                                      // stands in for Api.MUTATION_LOCK
                    try { r.check("race", "198.51.100.9"); } catch (Api.ApiException e) { return e.code; }
                    passwordChecks.incrementAndGet();                      // Auth.login with a wrong password
                    r.failure("race", "198.51.100.9");
                    return 401;
                }
            }));
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : attempts) codes.add(f.get(10, TimeUnit.SECONDS));
            pool.shutdown();
            check(passwordChecks.get() == 1 && codes.contains(401) && codes.contains(429), "only one concurrent attempt reaches the password check: " + codes);
            check(blocked(r, "race", "198.51.100.9"), "account is blocked after the race");
        }
        // Same for the source limit: 99 failures from one source, two simultaneous attempts with new names.
        LegacyLoginThrottle src = new LegacyLoginThrottle(now::get);
        for (int i = 0; i < LegacyLoginThrottle.MAX_SOURCE_FAILURES - 1; i++) src.failure("src-spray-" + i, "198.51.100.10");
        Object lock = new Object();
        CyclicBarrier both = new CyclicBarrier(2);
        AtomicInteger reached = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<Integer>> attempts = new ArrayList<>();
        for (int k = 0; k < 2; k++) { final String name = "src-new-" + k; attempts.add(pool.submit(() -> {
            try { src.check(name, "198.51.100.10"); } catch (Api.ApiException e) { return e.code; }
            both.await(5, TimeUnit.SECONDS);
            synchronized (lock) {
                try { src.check(name, "198.51.100.10"); } catch (Api.ApiException e) { return e.code; }
                reached.incrementAndGet(); src.failure(name, "198.51.100.10"); return 401;
            }
        })); }
        for (Future<Integer> f : attempts) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();
        check(reached.get() == 1, "source limit is also a hard limit under concurrency");

        System.out.println("LegacyLoginThrottleTest: " + checks + " checks passed; synthetic only, no database/listener/network.");
    }
}
