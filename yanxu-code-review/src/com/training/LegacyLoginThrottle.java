package com.training;

import java.util.*;
import java.util.function.LongSupplier;

/**
 * Failed-password throttle for the default "legacy" login mode, which previously had no attempt limit
 * (the email-verification mode already rate-limits through NotificationChannelsLoginVerification.preflight).
 * Only failures are counted; a successful login clears that account's failures. In-memory and bounded;
 * a restart clears it. It never changes accounts, roles or stored passwords.
 */
final class LegacyLoginThrottle {
    static final long WINDOW_MILLIS = 15 * 60 * 1000L;
    /** Per account name: enough for honest typos, far too few for password guessing. */
    static final int MAX_ACCOUNT_FAILURES = 10;
    /** Per source address: generous because one office NAT or reverse proxy may serve many people. */
    static final int MAX_SOURCE_FAILURES = 100;
    static final int MAX_KEYS = 10_000;

    private static final LegacyLoginThrottle DEFAULT = new LegacyLoginThrottle(System::currentTimeMillis);

    private final LongSupplier clock;
    private final LinkedHashMap<String, Deque<Long>> failures = new LinkedHashMap<>();

    LegacyLoginThrottle(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }

    static LegacyLoginThrottle shared() { return DEFAULT; }

    /** Throws 429 before any password work when this account or source has too many recent failures. */
    synchronized void check(String username, String source) throws Api.ApiException {
        long now = clock.getAsLong();
        prune(now);
        if (count(accountKey(username)) >= MAX_ACCOUNT_FAILURES || source != null && count(sourceKey(source)) >= MAX_SOURCE_FAILURES)
            throw new Api.ApiException(429, "登录失败次数过多，请15分钟后再试；如忘记密码请联系系统管理员重置");
    }

    synchronized void failure(String username, String source) {
        long now = clock.getAsLong();
        prune(now);
        record(accountKey(username), now);
        if (source != null) record(sourceKey(source), now);
    }

    synchronized void success(String username) { failures.remove(accountKey(username)); }

    synchronized int trackedKeys() { return failures.size(); }

    private int count(String key) { Deque<Long> times = failures.get(key); return times == null ? 0 : times.size(); }

    private void record(String key, long now) {
        Deque<Long> times = failures.get(key);
        if (times == null) {
            if (failures.size() >= MAX_KEYS) failures.remove(failures.keySet().iterator().next());
            times = new ArrayDeque<>();
            failures.put(key, times);
        }
        times.addLast(now);
        // Keep each list bounded; only the count within the window matters.
        while (times.size() > MAX_SOURCE_FAILURES) times.removeFirst();
    }

    private void prune(long now) {
        Iterator<Map.Entry<String, Deque<Long>>> entries = failures.entrySet().iterator();
        while (entries.hasNext()) {
            Deque<Long> times = entries.next().getValue();
            while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MILLIS) times.removeFirst();
            if (times.isEmpty()) entries.remove();
        }
    }

    private static String accountKey(String username) { return "account:" + (username == null ? "" : username); }
    private static String sourceKey(String source) { return "source:" + source; }
}
