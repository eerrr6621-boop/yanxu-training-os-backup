package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.training.RecommendationV2Fixture.*;
import static com.training.RecommendationDecision.map;

/**
 * Fresh synthetic H2 only (same isolated fixture as RecommendationV2HostTest; the data path must be a new empty
 * directory whose path contains "yanxu-recommendation-v2"). Covers review items 1, 2, 3, 7, 8 on the real host,
 * SQL and transactions: no listener, no network, no real data.
 */
public final class RecommendationScaleHostTest {
    static int checks; interface Work { void run() throws Exception; }
    static void ok(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    static void rejects(int code, Work action) throws Exception { try { action.run(); throw new AssertionError("Expected " + code); } catch (Api.ApiException e) { ok(e.code == code, "expected " + code + " actual " + e.code + " " + e.getMessage()); } }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Object x) { return (List<Map<String,Object>>) x; }
    static Map<String,Object> result(Map<String,Object> r) { return rows(r.get("results")).get(0); }
    static Map<String,Object> confirmation(Auth.Session actor, long id, String key) throws Exception { return map("id", id, "accept", 1, "expected_version", DispatchConfirmationGuard.preview(actor, id).get("expected_version"), "request_id", key); }
    static boolean canConfirm(long id) throws Exception { return Boolean.TRUE.equals(DispatchConfirmationGuard.preview(actors[0], id).get("can_confirm")); }
    static long dispatchOn(int teacher, String date, String start, String end) throws Exception { long id = dispatch(0, teacher, start, end); Db.exec("UPDATE dispatches SET teach_date=? WHERE id=?", date, id); return id; }
    static void history(long teacher, int count, String status, LocalDate first, int spreadDays, String subject) throws Exception {
        for (int i = 0; i < count; i++) Db.exec("INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,start_time,end_time,venue,hours,status) VALUES(?,?,?,?,'09:00','11:00','合成地点0',2,?)",
                projects[0], teacher, subject + i, first.plusDays(i % spreadDays).toString(), status);
    }
    static String state() throws Exception {
        List<Object> values = new ArrayList<>();
        for (String table : List.of("dispatches", "dispatch_confirmation_occupancy", "dispatch_confirmation_requests", "dispatch_confirmation_requests_archive")) values.add(Db.query("SELECT * FROM " + table + " ORDER BY 1,2"));
        return TrainingSummariesWorkflow.canonical(values);
    }

    public static void main(String[] args) throws Exception {
        long begin = System.nanoTime(); setup(Path.of(args[0]), "SYNTHETIC-SCALE-ONLY");
        try {
            // Item 1: one teacher with 5001 old confirmed/completed rows far from the request date.
            history(teachers.get(20), 5001, "已完成", LocalDate.of(2019, 1, 1), 1500, "历史课程");
            Map<String,Object> analysis = RecommendationDecisionHost.analyzeV2(actors[0], List.of(input(0)));
            ok(((Number) result(analysis).get("scanned")).intValue() == 179, "analysis succeeds with a teacher holding 5001 old rows");
            long heavy = dispatch(0, 20, "14:00", "15:00");
            ok(canConfirm(heavy), "preview of that teacher is not blocked by unrelated old rows");
            Map<String,Object> heavyBody = confirmation(actors[0], heavy, "scale-heavy-confirm");
            Map<String,Object> heavyResult = DispatchConfirmationGuard.confirm(actors[0], heavyBody);
            ok("CONFIRMED".equals(heavyResult.get("status")), "confirmation succeeds for a teacher with 5001 old rows");

            // Item 2: several teachers together above 5000 rows.
            history(teachers.get(21), 3000, "已完成", LocalDate.of(2020, 1, 1), 1000, "合计历史A");
            history(teachers.get(22), 3000, "已确认", LocalDate.of(2020, 6, 1), 1000, "合计历史B");
            ok(((Number) result(RecommendationDecisionHost.analyzeV2(actors[0], List.of(input(0)))).get("scanned")).intValue() == 179, "analysis succeeds with teachers above 5000 rows together");

            // Unknown dates keep failing closed wherever they are.
            Db.exec("INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,start_time,end_time,venue,hours,status) VALUES(?,?,'日期不明','2026-02-30','09:00','11:00','合成地点0',2,'已确认')", projects[0], teachers.get(23));
            ok(!canConfirm(dispatchOn(23, "2027-06-01", "09:00", "10:00")), "a confirmed row with a non-calendar date still blocks, even far away");
            Db.exec("INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,start_time,end_time,venue,hours,status) VALUES(?,?,'日期为空',NULL,'09:00','11:00','合成地点0',2,'已完成')", projects[0], teachers.get(19));
            ok(!canConfirm(dispatchOn(19, "2027-06-01", "09:00", "10:00")), "a completed row with an empty date still blocks");

            // Long frozen buffer from a booking 3 days earlier is still read and still conflicts.
            Map<String,Object> longBuffer = facts(0, 24);
            longBuffer.put("after_buffer_minutes", 5760); longBuffer.put("before_buffer_minutes", 60); longBuffer.put("door_to_door_minutes", 5760);
            longBuffer.put("leg_minutes", 120); longBuffer.put("transport_mode", "RAIL"); longBuffer.put("available_until", "2026-11-15T00:00:00Z");
            saveFacts(0, 24, 0, "scale-long-buffer", longBuffer);
            long first = dispatch(0, 24, "09:00", "11:00");
            DispatchConfirmationGuard.confirm(actors[0], confirmation(actors[0], first, "scale-long-buffer-confirm"));
            ok(!canConfirm(dispatchOn(24, "2026-11-13", "09:00", "10:00")), "a 4-day frozen after-buffer from 3 days earlier still conflicts");
            ok(canConfirm(dispatchOn(24, "2026-11-16", "09:00", "10:00")), "a day past that buffer is free");

            // Items 2/3: bounded history, newest teaching date first, usability checked before counting.
            long t25 = teachers.get(25);
            List<Integer> order = new ArrayList<>(); for (int i = 0; i < 300; i++) order.add(i); Collections.shuffle(order, new Random(25));
            for (int i : order) Db.exec("INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,start_time,end_time,venue,hours,status) VALUES(?,?,?,?,'09:00','11:00','合成地点0',2,'已完成')",
                    projects[0], t25, (i % 3 == 0 ? "不可用" : "可用") + i, LocalDate.of(2025, 1, 1).plusDays(i).toString());
            AtomicInteger examined = new AtomicInteger();
            List<Map<String,Object>> got = RecommendationDecisionHost.v2Bookings(Long.toString(t25), List.of(t25), "2026-11-10", row -> { examined.incrementAndGet(); return row.get("subject").toString().startsWith("可用"); });
            ok(got.size() == RecommendationDecisionHost.HISTORY_PER_TEACHER, "exactly 50 usable history rows: " + got.size());
            ok(got.stream().allMatch(r -> r.get("subject").toString().startsWith("可用")), "only usable rows are counted");
            List<String> dates = got.stream().map(r -> r.get("teach_date").toString()).toList();
            List<String> sorted = new ArrayList<>(dates); sorted.sort(Comparator.reverseOrder());
            ok(dates.equals(sorted) && dates.get(0).equals(LocalDate.of(2025, 1, 1).plusDays(299).toString()), "newest teaching date first, regardless of insertion id");
            ok(examined.get() <= RecommendationDecisionHost.HISTORY_SCAN_PER_TEACHER, "at most 200 rows examined: " + examined.get());
            examined.set(0);
            List<Map<String,Object>> sparse = RecommendationDecisionHost.v2Bookings(Long.toString(t25), List.of(t25), "2026-11-10", row -> { examined.incrementAndGet(); return false; });
            ok(sparse.isEmpty() && examined.get() == RecommendationDecisionHost.HISTORY_SCAN_PER_TEACHER, "scan stops at 200 rows when nothing is usable");

            // Items 7/8: archive keeps replay and request-id uniqueness; failures roll back.
            long active = Db.count("dispatch_confirmation_requests");
            ok(active == 2 && Db.count("dispatch_confirmation_requests_archive") == 0, "two active receipts before the archive test");
            String heavyDigest = (String) Db.one("SELECT digest FROM dispatch_confirmation_requests WHERE request_id='scale-heavy-confirm'").get("digest");
            DispatchConfirmationGuard.maxActiveRequests = 3;
            try {
                long third = dispatch(0, 26, "15:00", "16:00");
                DispatchConfirmationGuard.confirm(actors[0], confirmation(actors[0], third, "scale-third-confirm"));
                long fourth = dispatch(0, 27, "15:00", "16:00");
                Map<String,Object> fourthBody = confirmation(actors[0], fourth, "scale-fourth-confirm");
                String before = state();
                rejects(409, () -> DispatchConfirmationGuard.confirm(actors[0], fourthBody));
                ok(before.equals(state()), "full capacity with nothing old: confirmation refused and everything rolled back");

                Db.exec("UPDATE dispatches SET confirmed_at='2020-01-01T00:00:00.5Z' WHERE id=?", heavy);
                Db.exec("UPDATE dispatches SET confirmed_at='2021-01-01T00:00:00Z' WHERE id=?", first);
                DispatchConfirmationGuard.confirm(actors[0], confirmation(actors[0], fourth, "scale-fourth-confirm"));
                ok(Db.count("dispatch_confirmation_requests_archive") == 2 && Db.count("dispatch_confirmation_requests") == 2, "two receipts older than five years moved to the archive");
                ok(heavyDigest.equals(Db.one("SELECT digest FROM dispatch_confirmation_requests_archive WHERE request_id='scale-heavy-confirm'").get("digest")), "archived receipt kept byte for byte");

                String archived = state();
                Map<String,Object> replay = DispatchConfirmationGuard.confirm(actors[0], heavyBody);
                ok(Objects.equals(replay.get("result_version"), heavyResult.get("result_version")), "archived request replays the original receipt");
                ok(archived.equals(state()), "replay from the archive changes nothing");
                long other = dispatch(0, 28, "15:00", "16:00");
                Map<String,Object> reuse = confirmation(actors[0], other, "scale-heavy-confirm");
                rejects(409, () -> DispatchConfirmationGuard.confirm(actors[0], reuse));
                ok(archived.equals(state()), "an archived request id cannot be reused for another dispatch");
            } finally { DispatchConfirmationGuard.maxActiveRequests = DispatchConfirmationGuard.MAX_ACTIVE_REQUESTS; }

            System.out.println("RecommendationScaleHostTest: " + checks + " checks passed; elapsed_ms=" + (System.nanoTime() - begin) / 1000000 + "; fresh synthetic H2 only; no listener.");
        } finally { Db.exec("SHUTDOWN"); }
    }
}
