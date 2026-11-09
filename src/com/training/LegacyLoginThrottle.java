package com.training;

import java.util.*;
import java.util.function.LongSupplier;

/**
 * Failed-password throttle for the default "legacy" login mode, which previously had no attempt limit
 * (the email-verification mode already rate-limits through NotificationChannelsLoginVerification.preflight).
 * Only failures are counted; a successful login clears that account's failures. In-memory and bounded;
 * a restart clears it. It never changes accounts, roles or stored passwords.
 *
 * Concurrency: LoginVerificationHost calls check() once before the global business lock (early rejection) and again
 * inside it, immediately before the password check; failure/success are recorded inside the same lock. Every legacy
 * password check runs under that lock, so the limits are hard limits.
 *
 * Capacity: at MAX_KEYS, a new key may only displace the oldest key that is not currently blocked. If every tracked key
 * is blocked, attempts for keys that are not yet tracked are refused (429) until entries expire; an active block is
 * never dropped to make room.
 *
 * Unknown source: when the client address cannot be identified, failures count against one shared "unknown source"
 * key with the same per-source limit, so changing user names does not bypass the source limit.
 */
final class LegacyLoginThrottle {
    static final long WINDOW_MILLIS = 15 * 60 * 1000L;
    /** Per account name: enough for honest typos, far too few for password guessing. */
    static final int MAX_ACCOUNT_FAILURES = 10;
    /** Per source address: generous because one office NAT or reverse proxy may serve many people. */
    static final int MAX_SOURCE_FAILURES = 100;
    static final int MAX_KEYS = 10_000;
    static final String TOO_MANY = "登录失败次数过多，请15分钟后再试；如忘记密码请联系系统管理员重置";
    static final String SATURATED = "当前登录尝试过多，请稍后再试";

    private static final LegacyLoginThrottle DEFAULT = new LegacyLoginThrottle(System::currentTimeMillis);

    private final LongSupplier clock;
    private final LinkedHashMap<String, Deque<Long>> failures = new LinkedHashMap<>();
    private long lastFullPrune = Long.MIN_VALUE;

    LegacyLoginThrottle(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }

    static LegacyLoginThrottle shared() { return DEFAULT; }

    /** Throws 429 before any password work when this account or source has too many recent failures, or when the
     * table is full of active blocks and this attempt would need a new entry. */
    synchronized void check(String username, String source) throws Api.ApiException {
        long now = clock.getAsLong();
        prune(now);
        String account = accountKey(username), from = sourceKey(source);
        expire(account, now); expire(from, now);
        if (count(account) >= MAX_ACCOUNT_FAILURES || count(from) >= MAX_SOURCE_FAILURES)
            throw new Api.ApiException(429, TOO_MANY);
        int missing = (failures.containsKey(account) ? 0 : 1) + (failures.containsKey(from) ? 0 : 1);
        if (missing > 0 && failures.size() + missing > MAX_KEYS && evictable() < failures.size() + missing - MAX_KEYS)
            throw new Api.ApiException(429, SATURATED);
    }

    synchronized void failure(String username, String source) {
        long now = clock.getAsLong();
        prune(now);
        expire(accountKey(username), now); expire(sourceKey(source), now);
        record(accountKey(username), now);
        record(sourceKey(source), now);
    }

    synchronized void success(String username) { failures.remove(accountKey(username)); }

    synchronized int trackedKeys() { return failures.size(); }

    private int count(String key) { Deque<Long> times = failures.get(key); return times == null ? 0 : times.size(); }

    /** Expires one key's old failures (cheap, every call). */
    private void expire(String key, long now) {
        Deque<Long> times = failures.get(key);
        if (times == null) return;
        while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MILLIS) times.removeFirst();
        if (times.isEmpty()) failures.remove(key);
    }

    private boolean blocked(String key, Deque<Long> times) { return times.size() >= (key.startsWith("account:") ? MAX_ACCOUNT_FAILURES : MAX_SOURCE_FAILURES); }

    private int evictable() { int n = 0; for (var e : failures.entrySet()) if (!blocked(e.getKey(), e.getValue())) n++; return n; }

    private void record(String key, long now) {
        Deque<Long> times = failures.get(key);
        if (times == null) {
            if (failures.size() >= MAX_KEYS && !evictOldestUnblocked()) return; // check() already refused such attempts
            times = new ArrayDeque<>();
            failures.put(key, times);
        }
        times.addLast(now);
        // Keep each list bounded; only the count within the window matters.
        while (times.size() > MAX_SOURCE_FAILURES) times.removeFirst();
    }

    private boolean evictOldestUnblocked() {
        Iterator<Map.Entry<String, Deque<Long>>> entries = failures.entrySet().iterator();
        while (entries.hasNext()) { var e = entries.next(); if (!blocked(e.getKey(), e.getValue())) { entries.remove(); return true; } }
        return false;
    }

    /** Full sweep at most once a minute, and always when the table is full so capacity decisions see only live entries. */
    private void prune(long now) {
        if (failures.size() < MAX_KEYS && lastFullPrune != Long.MIN_VALUE && now - lastFullPrune < 60_000L) return;
        lastFullPrune = now;
        Iterator<Map.Entry<String, Deque<Long>>> entries = failures.entrySet().iterator();
        while (entries.hasNext()) {
            Deque<Long> times = entries.next().getValue();
            while (!times.isEmpty() && now - times.peekFirst() >= WINDOW_MILLIS) times.removeFirst();
            if (times.isEmpty()) entries.remove();
        }
    }

    private static String accountKey(String username) { return "account:" + (username == null ? "" : username); }
    /** Unidentified sources share one key (no real address contains a space). */
    private static String sourceKey(String source) { return source == null ? "source:unknown source" : "source:" + source; }
}
