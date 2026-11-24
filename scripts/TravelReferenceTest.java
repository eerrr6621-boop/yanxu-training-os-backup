package com.training;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * Pure synthetic checks for the city travel reference table and pre-fill rules: no database, network, model, HTTP or
 * saving. The saved-form path (endpoint, UI, save) is covered by RecommendationV2UI.test.cjs and the H2/HTTP tests.
 */
public final class TravelReferenceTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static final String HEADER = String.join(",", TravelReference.HEADER);
    private static final LocalDate TODAY = LocalDate.parse("2026-11-08");

    /** Ranks a saved pre-fill the way RecommendationDecisionHost does: only TOTAL_REQUEST costs are compared. */
    private static RecommendationRanking.Ranked ranked(Map<String, Object> f) {
        var request = new RecommendationDecision.Request("沟通", "SYNTHETIC-COURSE", "2026-11-20", "09:00", "11:00", "ONSITE", List.of());
        var candidate = new RecommendationDecision.Candidate(1, "合成教师", "在库", "合成甲城", List.of(), RecommendationDecision.Confirmation.CONFIRMED,
                RecommendationDecision.Confirmation.CONFIRMED, List.of(), List.of());
        var decision = RecommendationDecision.decide(new RecommendationDecision.Snapshot("SYNTHETIC", "c".repeat(64), request, List.of(candidate), true)).get(0);
        var window = new RecommendationRanking.Window(java.time.Instant.parse("2026-11-20T01:00:00Z"), java.time.Instant.parse("2026-11-20T03:00:00Z"));
        var context = new RecommendationRanking.Context("synthetic-request", false, "合成授课地点", window);
        String cost = (String) f.get("travel_cost");
        var comparable = cost != null && "TOTAL_REQUEST".equals(f.get("cost_coverage")) ? new RecommendationRanking.Cost(new java.math.BigDecimal(cost), "CNY", "TRAVEL_ONLY") : null;
        var travel = new RecommendationRanking.TravelFacts(1, "synthetic-request", RecommendationDecision.Confirmation.CONFIRMED,
                RecommendationRanking.TransportMode.valueOf((String) f.get("transport_mode")), (String) f.get("departure_place"), "合成授课地点",
                (Integer) f.get("leg_minutes"), (Integer) f.get("door_to_door_minutes"), (Integer) f.get("before_buffer_minutes"), (Integer) f.get("after_buffer_minutes"),
                null, comparable);
        var result = RecommendationRanking.rank(context, List.of(new RecommendationRanking.Input(decision, RecommendationRanking.IdentityStatus.CONFIRMED, travel)));
        check(result.normal().size() == 1, "pre-filled facts with confirmed service and availability give a normal candidate");
        return result.normal().get(0);
    }

    public static void main(String[] ignored) {
        String csv = HEADER + "\r\n"
                + "合成甲城,合成乙城,高铁,60,73,2026-11-01,核对人A,\r\n"                    // line 2
                + "合成甲城,合成乙城,飞机,55,600,2026-11-01,核对人A,\r\n"                   // line 3
                + "合成甲城市,合成丙城,高铁,240,300,2026/10/20,核对人B,\"含换乘,需早到\"\r\n" // line 4
                + "合成甲城,合成丙城,飞机,140,,2026-10-20,核对人B,\r\n"                     // line 5
                + "合成甲城,合成丁城,高铁,200,,2026-10-20,核对人B,\r\n"                     // line 6
                + "合成戊城,合成甲城,飞机,130,500,2026-11-02,核对人C,\r\n"                  // line 7
                + "合成甲城,合成己城,高铁,90,100,2025-10-01,核对人D,\r\n"                   // line 8
                + "合成甲城,合成甲城,高铁,10,5,2026-11-01,核对人E,\r\n"                     // line 9  same city
                + "合成甲城,合成庚城,火车,120,50,2026-11-01,核对人E,\r\n"                   // line 10 alias refused
                + "合成甲城,合成庚城,高铁,abc,50,2026-11-01,核对人E,\r\n"                   // line 11
                + "合成甲城,合成庚城,高铁,100,50,2026-11-01,,\r\n"                          // line 12 no checker
                + "合成甲城,合成乙城,高铁,65,73,2026-11-02,核对人F,\r\n"                    // line 13 duplicate
                + "合成甲城,合成辛城,高铁,70,80,2026-11-09,核对人G,\r\n"                    // line 14 future
                + "合成甲城,合成壬城,动车,70,80,2026-11-01,核对人G,\r\n"                    // line 15 alias refused
                + "合成甲城,合成癸城,飞机,70,80,2026-11-01,核对人G,\"多行\n备注\"\r\n"       // lines 16-17
                + "合成甲城,合成子城,高铁,7\"0\",80,2026-11-01,核对人G,\r\n"                // line 18 quote mid-field
                + "合成甲城,合成丑城,高铁,70,80,2026-11-01,核对人G,\"备注\"多余\r\n"         // line 19 text after closing quote
                + "合成癸城,合成甲城,飞机,75,90,2024-01-01,核对人H,\r\n"                    // line 20 expired opposite
                + "合成甲城,合成寅城,高铁,95,60,2025-01-01,核对人I,\r\n"                    // line 21 forward expired
                + "合成寅城,合成甲城,高铁,99,61,2026-10-01,核对人I,\r\n";                   // line 22 opposite current
        TravelReference.Table table = TravelReference.parse(csv, TODAY);
        check(table.present() && "OK".equals(table.state()) && table.rows().size() == 11, "eleven valid rows: " + table.rows().size());
        check(table.problems().size() == 9, "nine problem rows reported: " + table.problems());
        java.util.function.BiPredicate<String, String> problem = (line, text) -> table.problems().stream().anyMatch(p -> p.startsWith("第 " + line + " 行") && p.contains(text));
        check(problem.test("9", "同城"), "same-city row refused");
        check(problem.test("10", "高铁") && problem.test("15", "高铁"), "火车/动车 are not accepted as 高铁");
        check(problem.test("13", "重复"), "duplicate pair+mode refused, first kept");
        check(problem.test("14", "晚于今天"), "future check date refused");
        check(problem.test("18", "引号") && problem.test("19", "引号"), "quote in mid-field and text after a closing quote refused");
        check(table.rows().stream().anyMatch(r -> r.note().equals("含换乘,需早到")), "quoted comma kept in note");
        check(table.rows().stream().anyMatch(r -> r.line() == 16 && r.note().equals("多行\n备注")), "multi-line note read, record keeps its first physical line");
        check(table.rows().stream().anyMatch(r -> r.line() == 20) && table.rows().stream().noneMatch(r -> r.line() == 17), "lines after a multi-line note keep physical numbering");
        Map<String, Object> report = table.report();
        check(report.get("state").equals("OK") && report.get("usable_rows").equals(11) && report.get("problem_count").equals(9), "report gives state and counts");
        check(((List<?>) report.get("problems")).stream().allMatch(p -> !p.toString().contains("合成")), "report carries line numbers and reasons, never row contents");

        // Strict CSV: an unclosed quote makes record boundaries unreliable, so the whole file is refused.
        TravelReference.Table unclosed = TravelReference.parse(HEADER + "\n合成甲城,合成乙城,高铁,60,73,2026-11-01,核对人A,\"未闭合\n合成甲城,合成丙城,高铁,60,73,2026-11-01,核对人A,\n", TODAY);
        check(unclosed.rows().isEmpty() && "FORMAT_INVALID".equals(unclosed.state()) && unclosed.problems().get(0).contains("第 2 行") && unclosed.problems().get(0).contains("引号"), "unclosed quote rejects the file with its line: " + unclosed.problems());
        check(TravelReference.parse("1\"0\"0", TODAY).state().equals("HEADER_INVALID"), "quote error in the header is a header error");
        TravelReference.Table quotedDuration = TravelReference.parse(HEADER + "\n合成甲城,合成乙城,高铁,1\"0\"0,73,2026-11-01,核对人A,\n", TODAY);
        check(quotedDuration.rows().isEmpty() && quotedDuration.problems().get(0).contains("引号"), "1\"0\"0 is refused, not read as 100");

        // Table states are distinguishable.
        check(TravelReference.parse(HEADER + "\r\n", TODAY).state().equals("EMPTY"), "empty template: no reference rows yet");
        check(TravelReference.parse("", TODAY).state().equals("EMPTY"), "empty file");
        check(TravelReference.parse("城市,时间\n甲,1\n", TODAY).state().equals("HEADER_INVALID"), "wrong header");
        check(TravelReference.parse(HEADER + "\n合成甲城,合成乙城,汽车,60,73,2026-11-01,核对人A,\n", TODAY).state().equals("FORMAT_INVALID"), "file with only bad rows");
        StringBuilder many = new StringBuilder(HEADER + "\n"); for (int i = 0; i < 30; i++) many.append("合成甲城,合成城").append(i).append(",汽车,60,73,2026-11-01,核对人A,\n");
        check(((List<?>) TravelReference.parse(many.toString(), TODAY).report().get("problems")).size() == TravelReference.MAX_REPORTED_PROBLEMS, "at most 20 problems reported");

        // Encoding: UTF-8 with BOM and GBK (Excel on Chinese Windows) both read.
        String simple = HEADER + "\r\n合成甲城,合成乙城,高铁,60,73,2026-11-01,核对人A,\r\n";
        check(TravelReference.decode(("﻿" + simple).getBytes(StandardCharsets.UTF_8)).equals(simple), "UTF-8 BOM stripped");
        check(TravelReference.decode(simple.getBytes(Charset.forName("GBK"))).equals(simple), "GBK file decoded");

        // Same city: departure equals destination; no travel time.
        var local = TravelReference.suggest(table, "合成甲城", "合成甲城市", TODAY);
        check(local.status().equals("SAME_CITY") && local.facts().get("transport_mode").equals("LOCAL") && local.facts().get("before_buffer_minutes").equals(0)
                && local.facts().get("door_to_door_minutes").equals(0), "same city: local, zero travel time");

        // Owner decision 2026-11-08: only the listed rail/flight time counts, nothing is added.
        check(TravelReference.RAIL_ACCESS_MINUTES == 0 && TravelReference.AIR_ACCESS_MINUTES == 0, "no extra access minutes");
        var rail = TravelReference.suggest(table, "合成甲城", "合成乙城", TODAY);
        check(rail.status().equals("FOUND") && rail.facts().get("transport_mode").equals("RAIL") && rail.facts().get("leg_minutes").equals(60), "rail within 3h preferred over a faster flight");
        check(rail.facts().get("door_to_door_minutes").equals(60) && rail.facts().get("before_buffer_minutes").equals(60) && rail.facts().get("after_buffer_minutes").equals(60), "buffers equal the listed rail time");
        check(rail.facts().get("travel_cost").equals("73") && rail.facts().get("currency").equals("CNY") && rail.facts().get("cost_coverage").equals("ONE_WAY"), "fare is one-way CNY");
        check(rail.facts().get("valid_until").equals("2027-10-31T16:00:00Z"), "usable one year after the check date (Asia/Shanghai midnight)");
        check(((String) rail.facts().get("source_note")).contains("第 2 行") && ((String) rail.facts().get("source_note")).contains("核对人A") && !((String) rail.facts().get("source_note")).contains("另加"), "note cites the row and checker, no added time");
        check(rail.facts().get("travel_feasibility").equals("CONFIRMED") && !rail.facts().containsKey("service") && !rail.facts().containsKey("availability"), "only travel fields suggested");
        check(rail.table().get("problem_count").equals(9), "suggestion carries the table report");

        var air = TravelReference.suggest(table, "合成甲城", "合成丙城", TODAY);
        check(air.facts().get("transport_mode").equals("AIR") && air.facts().get("door_to_door_minutes").equals(140), "air when rail over 3h and air within 2.5h; listed time only");
        check(air.facts().get("travel_cost") == null && air.facts().get("currency").equals("") && air.facts().get("cost_coverage").equals(""), "unknown fare left empty");
        check(TravelReference.suggest(table, "合成甲城", "合成丁城", TODAY).facts().get("leg_minutes").equals(200), "over-3h rail still suggested, not excluded");

        // Actual departure city decides the lookup (the host passes it, else the base city).
        check(TravelReference.suggest(table, "合成乙城", "合成乙城", TODAY).status().equals("SAME_CITY"), "same city only when the actual departure equals the destination");
        check(TravelReference.suggest(table, "合成戊城", "合成乙城", TODAY).status().equals("NOT_FOUND"), "a different actual departure is looked up as itself");

        // Opposite direction: marked reference, also when the forward row has expired.
        var reverse = TravelReference.suggest(table, "合成甲城", "合成戊城", TODAY);
        check(reverse.status().equals("FOUND_REVERSE") && reverse.message().contains("反方向参考") && ((String) reverse.facts().get("source_note")).startsWith("【反方向参考"), "opposite row offered as a marked reference");
        var expiredForward = TravelReference.suggest(table, "合成甲城", "合成寅城", TODAY);
        check(expiredForward.status().equals("FOUND_REVERSE") && expiredForward.facts().get("leg_minutes").equals(99), "expired forward row falls back to a current opposite row");
        check(TravelReference.suggest(table, "合成甲城", "合成癸城", TODAY).status().equals("FOUND"), "a current forward row is used before any opposite row");

        // Validity boundaries: check day is day 1, day 365 usable, day 366 not; future never.
        TravelReference.Row row = new TravelReference.Row(2, "a", "b", "RAIL", 60, null, LocalDate.parse("2026-01-01"), "x", "");
        check(TravelReference.current(row, LocalDate.parse("2026-01-01")) && TravelReference.current(row, LocalDate.parse("2026-12-31")), "check day and day 365 usable");
        check(!TravelReference.current(row, LocalDate.parse("2027-01-01")), "day 366 expired");
        check(!TravelReference.current(row, LocalDate.parse("2025-12-31")), "a row checked after today is never current");
        check(TravelReference.suggest(table, "合成甲城", "合成己城", TODAY).status().equals("EXPIRED"), "rows checked more than a year ago refused");
        check(TravelReference.REVIEW_DAYS == 365, "review period is the confirmed one year");

        check(TravelReference.suggest(table, "合成甲城", "合成辛城", TODAY).status().equals("NOT_FOUND"), "future row is not usable");
        check(TravelReference.suggest(table, "", "合成乙城", TODAY).status().equals("BASE_CITY_UNKNOWN"), "no departure city is not guessed");
        check(TravelReference.suggest(table, "合成甲城", " ", TODAY).status().equals("DESTINATION_REQUIRED"), "destination required");
        check(TravelReference.suggest(new TravelReference.Table(List.of(), List.of(), false), "合成甲城", "合成乙城", TODAY).status().equals("NOT_CONFIGURED"), "missing file is a normal state");
        check(TravelReference.suggest(new TravelReference.Table(List.of(), List.of(), false), "合成甲城", "合成甲城", TODAY).status().equals("SAME_CITY"), "same city works without any table");
        var broken = TravelReference.suggest(TravelReference.parse("城市,时间\n", TODAY), "合成甲城", "合成乙城", TODAY);
        check(broken.status().equals("TABLE_INVALID") && broken.message().contains("表头"), "a broken file is reported as broken, not as a missing route");

        check(TravelReference.choose(List.of(new TravelReference.Row(2, "a", "b", "AIR", 160, null, TODAY, "x", ""), new TravelReference.Row(3, "a", "b", "RAIL", 200, null, TODAY, "x", ""))).mode().equals("RAIL"),
                "neither within preference: rail listed first by principle");

        // With the ranking rules: a saved rail pre-fill ranks in the "rail within 3h" tier; same city in the local tier.
        var railRank = ranked(rail.facts());
        check(railRank.tier() == RecommendationRanking.Tier.RAIL_WITHIN_180, "pre-filled rail ranks within 3h tier");
        check(ranked(air.facts()).tier() == RecommendationRanking.Tier.AIR_WITHIN_150, "pre-filled air ranks within 2.5h tier");
        check(ranked(local.facts()).tier() == RecommendationRanking.Tier.LOCAL_OR_ONLINE, "same city ranks in local tier");
        check(railRank.missingInfo().contains("COST_UNKNOWN"), "a one-way fare is not used as a whole-request cost (host rule: only TOTAL_REQUEST)");
        System.out.println("TravelReferenceTest: " + checks + " checks passed; synthetic only, no database/network/model.");
    }
}
