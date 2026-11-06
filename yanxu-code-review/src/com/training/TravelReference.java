package com.training;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Staff-reviewed city-to-city travel reference, read from data/travel-reference.csv (prepared offline, e.g. drafted
 * with an external tool and then checked by a person). The server only looks rows up: no network, no model, no
 * automatic saving. A suggestion only pre-fills the staff assessment form; nothing counts until staff save it.
 *
 * Confirmed 2026-11-06: same city needs no travel time; staff confirm every pre-fill. Station/airport access defaults
 * below follow the owner's accepted example and are adjustable here.
 */
final class TravelReference {
    private TravelReference() {}

    static final String FILE_NAME = "travel-reference.csv";
    static final List<String> HEADER = List.of("出发城市", "到达城市", "交通方式", "单程时长分钟", "单程参考票价元", "核对日期", "核对人", "备注");
    /** Extra door-to-door time on top of the city-to-city leg (getting to the station/airport, waiting, reaching the venue). */
    static final int RAIL_ACCESS_MINUTES = 90, AIR_ACCESS_MINUTES = 150;
    /** A row is usable for this many days after it was checked (same default as CityTravelReference). */
    static final int REVIEW_DAYS = 90;
    static final int MAX_BYTES = 2 * 1024 * 1024, MAX_ROWS = 5000;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    record Row(int line, String from, String to, String mode, int minutes, BigDecimal fare, LocalDate checked, String checker, String note) {}
    record Table(List<Row> rows, List<String> problems, boolean present) {}
    record Suggestion(String status, String message, Map<String, Object> facts) {
        Map<String, Object> value() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status); result.put("message", message); result.put("suggestion", facts);
            result.put("statement", "参考表只用于预填，须由有权操作人核对后点击保存才生效；不调用任何模型。");
            return result;
        }
    }

    private static Path cachedPath; private static long cachedStamp = -1, cachedSize = -1; private static Table cached;

    /** Reads the reference beside the database (same data.dir). Missing file is a normal "not configured" state. */
    static synchronized Table load() {
        String dir = System.getProperty("data.dir", "data").trim();
        Path path = Paths.get(dir.isEmpty() ? "data" : dir, FILE_NAME).toAbsolutePath().normalize();
        try {
            if (!Files.isRegularFile(path)) return new Table(List.of(), List.of(), false);
            long stamp = Files.getLastModifiedTime(path).toMillis(), size = Files.size(path);
            if (path.equals(cachedPath) && stamp == cachedStamp && size == cachedSize) return cached;
            if (size > MAX_BYTES) return new Table(List.of(), List.of("参考表超过 2MB，请拆分或精简"), true);
            Table table = parse(decode(Files.readAllBytes(path)));
            cachedPath = path; cachedStamp = stamp; cachedSize = size; cached = table;
            return table;
        } catch (Exception unreadable) {
            return new Table(List.of(), List.of("参考表无法读取，请检查文件"), true);
        }
    }

    /** Excel on Chinese Windows often saves CSV as GBK; accept UTF-8 (with or without BOM) first, then GB18030. */
    static String decode(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return text.startsWith("﻿") ? text.substring(1) : text;
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, Charset.forName("GB18030"));
        }
    }

    /** Strict parse: bad rows are skipped and reported with their line number, never guessed. */
    static Table parse(String csv) {
        List<List<String>> records = records(csv);
        List<String> problems = new ArrayList<>(); List<Row> rows = new ArrayList<>();
        if (records.isEmpty()) return new Table(List.of(), List.of("参考表为空"), true);
        List<String> header = records.get(0).stream().map(String::strip).toList();
        if (!header.equals(HEADER)) return new Table(List.of(), List.of("表头须为：" + String.join(",", HEADER)), true);
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> r = records.get(i); int line = i + 1;
            if (r.stream().allMatch(String::isBlank)) continue;
            if (rows.size() >= MAX_ROWS) { problems.add("超过 " + MAX_ROWS + " 行，其余未读取"); break; }
            if (r.size() != HEADER.size()) { problems.add("第 " + line + " 行列数不对"); continue; }
            String from = r.get(0).strip(), to = r.get(1).strip(), modeText = r.get(2).strip();
            String mode = switch (modeText) { case "高铁", "火车", "动车" -> "RAIL"; case "飞机", "航班" -> "AIR"; default -> ""; };
            if (city(from).isEmpty() || city(to).isEmpty() || from.length() > 64 || to.length() > 64) { problems.add("第 " + line + " 行城市为空或过长"); continue; }
            if (city(from).equals(city(to))) { problems.add("第 " + line + " 行为同城，无需填写"); continue; }
            if (mode.isEmpty()) { problems.add("第 " + line + " 行交通方式须为“高铁”或“飞机”"); continue; }
            Integer minutes = wholeNumber(r.get(3).strip());
            if (minutes == null || minutes < 1 || minutes > 2880) { problems.add("第 " + line + " 行单程时长须为 1 至 2880 的整数分钟"); continue; }
            BigDecimal fare = null; String fareText = r.get(4).strip();
            if (!fareText.isEmpty()) { if (!fareText.matches("[0-9]{1,7}(?:\\.[0-9]{1,2})?")) { problems.add("第 " + line + " 行票价须为金额或留空"); continue; } fare = new BigDecimal(fareText); }
            LocalDate checked;
            try { checked = LocalDate.parse(r.get(5).strip().replace('/', '-')); } catch (DateTimeException bad) { problems.add("第 " + line + " 行核对日期须为 2026-11-06 这样的格式"); continue; }
            String checker = r.get(6).strip();
            if (checker.isEmpty() || checker.length() > 64) { problems.add("第 " + line + " 行须填写核对人"); continue; }
            if (!seen.add(city(from) + "|" + city(to) + "|" + mode)) { problems.add("第 " + line + " 行与前面的同一城市对、同一交通方式重复，未采用"); continue; }
            String note = r.get(7).strip(); if (note.length() > 200) note = note.substring(0, 200);
            rows.add(new Row(line, from, to, mode, minutes, fare, checked, checker, note));
        }
        return new Table(List.copyOf(rows), List.copyOf(problems), true);
    }

    /** City names compare without spaces and a trailing “市”, so “杭州” and “杭州市” match. */
    static String city(String raw) {
        String value = raw == null ? "" : raw.replaceAll("\\s+", "");
        return value.endsWith("市") && value.length() > 1 ? value.substring(0, value.length() - 1) : value;
    }

    static Suggestion suggest(Table table, String baseCity, String destinationCity, LocalDate today) {
        String from = city(baseCity), to = city(destinationCity);
        if (to.isEmpty()) return new Suggestion("DESTINATION_REQUIRED", "请先填写授课城市。", null);
        if (from.isEmpty()) return new Suggestion("BASE_CITY_UNKNOWN", "该教师档案没有常驻城市，无法查参考表；请先补充档案或手工填写。", null);
        if (from.equals(to)) {
            Map<String, Object> facts = facts("LOCAL", baseCity.strip(), 0, 0, null, today.plusDays(REVIEW_DAYS),
                    "按交通参考规则预填：常驻城市与授课城市同为" + to + "，同城不留路上时间。");
            return new Suggestion("SAME_CITY", "同城：已预填本地出行、不留路上时间。请核对后点击保存。", facts);
        }
        if (!table.present()) return new Suggestion("NOT_CONFIGURED", "服务器上还没有交通参考表，请手工填写出行信息。", null);
        List<Row> pair = new ArrayList<>();
        for (Row row : table.rows()) if (city(row.from()).equals(from) && city(row.to()).equals(to)) pair.add(row);
        boolean reverse = false;
        if (pair.isEmpty()) {
            for (Row row : table.rows()) if (city(row.from()).equals(to) && city(row.to()).equals(from)) pair.add(row);
            reverse = !pair.isEmpty();
        }
        if (pair.isEmpty()) return new Suggestion("NOT_FOUND", "参考表中没有“" + from + "→" + to + "”，请手工填写或先补充参考表。", null);
        List<Row> current = pair.stream().filter(row -> today.isBefore(row.checked().plusDays(REVIEW_DAYS))).toList();
        if (current.isEmpty()) return new Suggestion("EXPIRED", "参考表中“" + from + "→" + to + "”已超过 " + REVIEW_DAYS + " 天未核对，请先更新参考表再预填。", null);
        Row chosen = choose(current);
        int access = chosen.mode().equals("RAIL") ? RAIL_ACCESS_MINUTES : AIR_ACCESS_MINUTES;
        String modeName = chosen.mode().equals("RAIL") ? "高铁" : "飞机";
        String note = "按交通参考表预填（第 " + chosen.line() + " 行" + (reverse ? "，按反方向记录" : "") + "）：" + from + "→" + to + " " + modeName + "约 "
                + chosen.minutes() + " 分钟，另加进出站等 " + access + " 分钟；参考表核对于 " + chosen.checked() + "（核对人 " + chosen.checker() + "）"
                + (chosen.note().isEmpty() ? "" : "；备注：" + chosen.note()) + "。";
        Map<String, Object> facts = facts(chosen.mode(), baseCity.strip(), chosen.minutes(), access, chosen.fare(), chosen.checked().plusDays(REVIEW_DAYS), note);
        return new Suggestion("FOUND", "已按参考表预填 " + modeName + " 出行信息，请核对后点击保存。服务意愿和完整档期仍需单独核实。", facts);
    }

    /** Owner's principle: high-speed rail within 3 hours first; otherwise air within 2.5 hours; otherwise rail if listed, else air. */
    static Row choose(List<Row> rows) {
        Comparator<Row> shortest = Comparator.comparingInt(Row::minutes);
        Optional<Row> rail = rows.stream().filter(r -> r.mode().equals("RAIL")).min(shortest), air = rows.stream().filter(r -> r.mode().equals("AIR")).min(shortest);
        if (rail.isPresent() && rail.get().minutes() <= 180) return rail.get();
        if (air.isPresent() && air.get().minutes() <= 150) return air.get();
        return rail.orElseGet(air::get);
    }

    /** Keys match the staff form's travel fields; leg + access = door to door, used as both the before and after buffer. */
    private static Map<String, Object> facts(String mode, String departure, int leg, int access, BigDecimal fare, LocalDate usableUntil, String note) {
        int door = leg + access;
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("travel_feasibility", "CONFIRMED");
        facts.put("transport_mode", mode);
        facts.put("departure_place", departure);
        facts.put("leg_minutes", leg);
        facts.put("door_to_door_minutes", door);
        facts.put("before_buffer_minutes", door);
        facts.put("after_buffer_minutes", door);
        facts.put("travel_cost", fare == null ? null : fare.toPlainString());
        facts.put("currency", fare == null ? "" : "CNY");
        facts.put("cost_coverage", fare == null ? "" : "ONE_WAY");
        facts.put("valid_until", usableUntil.atStartOfDay(ZONE).toInstant().toString());
        facts.put("source_note", note);
        return facts;
    }

    private static Integer wholeNumber(String text) {
        if (!text.matches("[0-9]{1,5}")) return null;
        return Integer.parseInt(text);
    }

    /** Minimal RFC 4180 reader: commas, quoted fields, doubled quotes, CRLF/LF. */
    static List<List<String>> records(String csv) {
        List<List<String>> out = new ArrayList<>(); List<String> row = new ArrayList<>(); StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') { if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') { cell.append('"'); i++; } else quoted = false; }
                else cell.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0); out.add(row); row = new ArrayList<>();
            } else cell.append(c);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); out.add(row); }
        return out;
    }
}
