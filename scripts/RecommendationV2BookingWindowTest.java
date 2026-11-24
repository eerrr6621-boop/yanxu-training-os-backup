package com.training;

import java.util.*;
import static com.training.RecommendationDecision.*;

/**
 * Pure synthetic check (no database): reading only the rows selected by
 * RecommendationDecisionHost.conflictRelevantDates must give exactly the same decide() result
 * as reading a teacher's entire confirmed/completed history, which previously failed beyond 5000 rows.
 */
public final class RecommendationV2BookingWindowTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private record Stored(String date, String start, String end, String status) {}

    private static Candidate candidate(List<Booking> bookings) {
        return new Candidate(7, "合成教师", "在库", "合成城市", List.of(), Confirmation.CONFIRMED, Confirmation.CONFIRMED, bookings, List.of());
    }
    private static String summary(Request request, List<Booking> bookings) {
        Decision d = decide(new Snapshot("SYNTHETIC", "b".repeat(64), request, List.of(candidate(bookings)), true)).get(0);
        return d.state() + "|" + d.checks().stream().map(c -> c.code() + ":" + c.state()).toList();
    }
    private static Booking booking(Stored s) { return new Booking(s.date() == null ? "" : s.date(), s.start(), s.end(), s.status()); }

    public static void main(String[] ignored) {
        Random random = new Random(20261106L);
        String[] odd = {null, "", "2026-02-30", "2026/11/01", "11-01", "2026-13-01"};
        String[] times = {"08:00", "09:00", "10:30", "12:00", "13:00", "15:00", "18:00"};
        int differentOutcomes = 0;
        for (int round = 0; round < 3000; round++) {
            List<Stored> history = new ArrayList<>();
            int size = 1 + random.nextInt(40);
            for (int i = 0; i < size; i++) {
                String date = random.nextInt(12) == 0 ? odd[random.nextInt(odd.length)]
                        : String.format("2026-%02d-%02d", 1 + random.nextInt(12), 1 + random.nextInt(28));
                String a = times[random.nextInt(times.length)], b = times[random.nextInt(times.length)];
                if (random.nextInt(15) == 0) b = "";
                history.add(new Stored(date, a, b, random.nextBoolean() ? "已确认" : "已完成"));
            }
            String day = random.nextInt(10) == 0 ? "" : (random.nextBoolean() && !history.isEmpty() && history.get(0).date() != null && history.get(0).date().matches("\\d{4}-\\d{2}-\\d{2}") && validDate(history.get(0).date()) ? history.get(0).date()
                    : String.format("2026-%02d-%02d", 1 + random.nextInt(12), 1 + random.nextInt(28)));
            Request request = day.isEmpty() ? new Request("沟通", "", "", "", "", "ONSITE", List.of())
                    : new Request("沟通", "", day, "09:00", "12:00", "ONSITE", List.of());

            List<Object> distinct = new ArrayList<>(new LinkedHashSet<>(history.stream().map(Stored::date).toList()));
            var relevant = RecommendationDecisionHost.conflictRelevantDates(day, distinct);
            List<Booking> all = history.stream().map(RecommendationV2BookingWindowTest::booking).toList();
            List<Booking> narrowed = history.stream()
                    .filter(s -> s.date() == null ? relevant.includeMissing() : relevant.dates().contains(s.date()))
                    .map(RecommendationV2BookingWindowTest::booking).toList();
            String full = summary(request, all), narrow = summary(request, narrowed);
            check(full.equals(narrow), "narrowed read changes the decision: " + full + " vs " + narrow);
            if (!full.equals(summary(request, List.of()))) differentOutcomes++;
        }
        check(differentOutcomes > 100, "fixture exercises real conflicts/uncertain bookings, not only empty cases");
        var plain = RecommendationDecisionHost.conflictRelevantDates("2026-11-01", List.of("2026-10-31", "2026-11-01", "2026-11-02"));
        check(plain.dates().equals(List.of("2026-11-01")) && !plain.includeMissing(), "valid other days are never read for conflicts");
        var unknown = RecommendationDecisionHost.conflictRelevantDates("", Arrays.asList("2026-11-01", "2026-02-30", null));
        check(unknown.dates().equals(List.of("2026-02-30")) && unknown.includeMissing(), "invalid and missing dates are still read");
        check(RecommendationDecisionHost.HISTORY_PER_TEACHER == 50, "history clue window is the confirmed 50 rows");
        System.out.println("RecommendationV2BookingWindowTest: " + checks + " checks passed; 3000 synthetic histories, no database.");
    }
}
