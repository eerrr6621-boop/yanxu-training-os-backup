package com.training;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** Pure synthetic checks for the city travel reference table and pre-fill rules: no database, network or model. */
public final class TravelReferenceTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static final String HEADER = String.join(",", TravelReference.HEADER);
    private static final LocalDate TODAY = LocalDate.parse("2026-11-06");

    private static RecommendationRanking.Tier tier(Map<String, Object> f) {
        var request = new RecommendationDecision.Request("沟通", "SYNTHETIC-COURSE", "2026-11-20", "09:00", "11:00", "ONSITE", List.of());
        var candidate = new RecommendationDecision.Candidate(1, "合成教师", "在库", "合成甲城", List.of(), RecommendationDecision.Confirmation.CONFIRMED,
                RecommendationDecision.Confirmation.CONFIRMED, List.of(), List.of());
        var decision = RecommendationDecision.decide(new RecommendationDecision.Snapshot("SYNTHETIC", "c".repeat(64), request, List.of(candidate), true)).get(0);
        var window = new RecommendationRanking.Window(java.time.Instant.parse("2026-11-20T01:00:00Z"), java.time.Instant.parse("2026-11-20T03:00:00Z"));
        var context = new RecommendationRanking.Context("synthetic-request", false, "合成授课地点", window);
        String cost = (String) f.get("travel_cost");
        var travel = new RecommendationRanking.TravelFacts(1, "synthetic-request", RecommendationDecision.Confirmation.CONFIRMED,
                RecommendationRanking.TransportMode.valueOf((String) f.get("transport_mode")), (String) f.get("departure_place"), "合成授课地点",
                (Integer) f.get("leg_minutes"), (Integer) f.get("door_to_door_minutes"), (Integer) f.get("before_buffer_minutes"), (Integer) f.get("after_buffer_minutes"),
                null, cost == null ? null : new RecommendationRanking.Cost(new java.math.BigDecimal(cost), "CNY", "TRAVEL_ONLY"));
        var result = RecommendationRanking.rank(context, List.of(new RecommendationRanking.Input(decision, RecommendationRanking.IdentityStatus.CONFIRMED, travel)));
        check(result.normal().size() == 1, "pre-filled facts with confirmed service and availability give a normal candidate");
        return result.normal().get(0).tier();
    }

    public static void main(String[] ignored) {
        String csv = HEADER + "\r\n"
                + "合成甲城,合成乙城,高铁,60,73,2026-11-01,核对人A,\r\n"
                + "合成甲城,合成乙城,飞机,55,600,2026-11-01,核对人A,\r\n"
                + "合成甲城市,合成丙城,高铁,240,300,2026/10/20,核对人B,\"含换乘,需早到\"\r\n"
                + "合成甲城,合成丙城,飞机,140,,2026-10-20,核对人B,\r\n"
                + "合成甲城,合成丁城,高铁,200,,2026-10-20,核对人B,\r\n"
                + "合成戊城,合成甲城,飞机,130,500,2026-11-02,核对人C,\r\n"
                + "合成甲城,合成己城,高铁,90,100,2025-10-01,核对人D,\r\n"
                + "合成甲城,合成甲城,高铁,10,5,2026-11-01,核对人E,\r\n"
                + "合成甲城,合成庚城,汽车,120,50,2026-11-01,核对人E,\r\n"
                + "合成甲城,合成庚城,高铁,abc,50,2026-11-01,核对人E,\r\n"
                + "合成甲城,合成庚城,高铁,100,50,2026-11-01,,\r\n"
                + "合成甲城,合成乙城,高铁,65,73,2026-11-02,核对人F,\r\n";
        TravelReference.Table table = TravelReference.parse(csv);
        check(table.present() && table.rows().size() == 7, "seven valid rows: " + table.rows().size());
        check(table.problems().size() == 5, "five problem rows reported with line numbers: " + table.problems());
        check(table.problems().stream().anyMatch(p -> p.contains("第 9 行") && p.contains("同城")), "same-city row refused");
        check(table.problems().stream().anyMatch(p -> p.contains("第 13 行") && p.contains("重复")), "duplicate pair+mode refused, first kept");
        check(table.rows().stream().anyMatch(r -> r.note().equals("含换乘,需早到")), "quoted comma kept in note");
        check(TravelReference.parse("城市,时间\n甲,1\n").rows().isEmpty() && !TravelReference.parse("城市,时间\n").problems().isEmpty(), "wrong header refused");

        // Encoding: UTF-8 with BOM and GBK (Excel on Chinese Windows) both read.
        check(TravelReference.decode(("﻿" + csv).getBytes(StandardCharsets.UTF_8)).equals(csv), "UTF-8 BOM stripped");
        check(TravelReference.decode(csv.getBytes(Charset.forName("GBK"))).equals(csv), "GBK file decoded");

        // Same city: no table needed, no travel time.
        var local = TravelReference.suggest(table, "合成甲城", "合成甲城市", TODAY);
        check(local.status().equals("SAME_CITY") && local.facts().get("transport_mode").equals("LOCAL") && local.facts().get("before_buffer_minutes").equals(0)
                && local.facts().get("door_to_door_minutes").equals(0), "same city: local, zero travel time");

        // Rail within 3h preferred over a faster flight.
        var rail = TravelReference.suggest(table, "合成甲城", "合成乙城", TODAY);
        check(rail.status().equals("FOUND") && rail.facts().get("transport_mode").equals("RAIL") && rail.facts().get("leg_minutes").equals(60), "rail within 3h preferred");
        check(rail.facts().get("door_to_door_minutes").equals(60 + TravelReference.RAIL_ACCESS_MINUTES) && rail.facts().get("before_buffer_minutes").equals(150)
                && rail.facts().get("after_buffer_minutes").equals(150), "rail adds 90 minutes access, used before and after");
        check(rail.facts().get("travel_cost").equals("73") && rail.facts().get("currency").equals("CNY") && rail.facts().get("cost_coverage").equals("ONE_WAY"), "fare is one-way CNY");
        check(rail.facts().get("valid_until").equals("2027-10-31T16:00:00Z"), "usable one year after the check date (Asia/Shanghai midnight)");
        check(((String) rail.facts().get("source_note")).contains("第 2 行") && ((String) rail.facts().get("source_note")).contains("核对人A"), "note cites the row and checker");
        check(rail.facts().get("travel_feasibility").equals("CONFIRMED") && !rail.facts().containsKey("service") && !rail.facts().containsKey("availability"), "only travel fields suggested");

        // Rail over 3h, flight within 2.5h: flight; unknown fare stays empty, never zero.
        var air = TravelReference.suggest(table, "合成甲城", "合成丙城", TODAY);
        check(air.facts().get("transport_mode").equals("AIR") && air.facts().get("door_to_door_minutes").equals(140 + TravelReference.AIR_ACCESS_MINUTES), "air when rail over 3h and air within 2.5h");
        check(air.facts().get("travel_cost") == null && air.facts().get("currency").equals("") && air.facts().get("cost_coverage").equals(""), "unknown fare left empty");

        // Only slow rail listed: still suggested (preference, not a ban).
        var slow = TravelReference.suggest(table, "合成甲城", "合成丁城", TODAY);
        check(slow.status().equals("FOUND") && slow.facts().get("leg_minutes").equals(200), "over-3h rail still suggested, not excluded");

        // Reverse direction used when only the return leg is listed.
        var reverse = TravelReference.suggest(table, "合成甲城", "合成戊城", TODAY);
        check(reverse.status().equals("FOUND") && ((String) reverse.facts().get("source_note")).contains("反方向"), "reverse row used and disclosed");

        check(TravelReference.suggest(table, "合成甲城", "合成己城", TODAY).status().equals("EXPIRED"), "rows checked more than a year ago refused");
        check(TravelReference.REVIEW_DAYS == 365, "review period is the confirmed one year");
        check(TravelReference.suggest(table, "合成甲城", "合成辛城", TODAY).status().equals("NOT_FOUND"), "unknown pair not guessed");
        check(TravelReference.suggest(table, "", "合成乙城", TODAY).status().equals("BASE_CITY_UNKNOWN"), "teacher without base city not guessed");
        check(TravelReference.suggest(table, "合成甲城", " ", TODAY).status().equals("DESTINATION_REQUIRED"), "destination required");
        check(TravelReference.suggest(new TravelReference.Table(List.of(), List.of(), false), "合成甲城", "合成乙城", TODAY).status().equals("NOT_CONFIGURED"), "missing file is a normal state");
        check(TravelReference.suggest(new TravelReference.Table(List.of(), List.of(), false), "合成甲城", "合成甲城", TODAY).status().equals("SAME_CITY"), "same city works without any table");

        // A pre-filled record would rank: rail 60 min sits in the within-3h tier.
        check(TravelReference.choose(List.of(new TravelReference.Row(2, "a", "b", "AIR", 160, null, TODAY, "x", ""), new TravelReference.Row(3, "a", "b", "RAIL", 200, null, TODAY, "x", ""))).mode().equals("RAIL"),
                "neither within preference: rail listed first by principle");
        // End to end with the ranking rules: a saved rail pre-fill ranks in the "rail within 3h" tier; same city in the local tier.
        check(tier(rail.facts()) == RecommendationRanking.Tier.RAIL_WITHIN_180, "pre-filled rail ranks within 3h tier");
        check(tier(air.facts()) == RecommendationRanking.Tier.AIR_WITHIN_150, "pre-filled air ranks within 2.5h tier");
        check(tier(local.facts()) == RecommendationRanking.Tier.LOCAL_OR_ONLINE, "same city ranks in local tier");
        System.out.println("TravelReferenceTest: " + checks + " checks passed; synthetic only, no database/network/model.");
    }
}
