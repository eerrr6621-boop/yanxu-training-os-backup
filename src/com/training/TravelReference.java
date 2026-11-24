package com.training;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/**
 * City-to-city travel reference, read from data/travel-reference.csv (prepared offline). The server only looks rows up:
 * no network, no model, no automatic saving. A suggestion only pre-fills the staff assessment form; nothing counts until
 * staff review it and save.
 *
 * Business rules confirmed by the owner:
 * - 2026-11-06: same city needs no travel time; staff confirm every pre-fill before saving.
 * - 2026-11-07: a row is usable for one year from its check date.
 * - 2026-11-08: only the rail or flight time itself counts; no extra station/airport time is added.
 * - 2026-11-08: when only the opposite direction is listed, it may be offered as a clearly marked "反方向参考".
 */
final class TravelReference {
    private TravelReference() {}

    static final String FILE_NAME = "travel-reference.csv";
    static final List<String> HEADER = List.of("出发城市", "到达城市", "交通方式", "单程时长分钟", "单程参考票价元", "核对日期", "核对人", "备注");
    /** Extra time on top of the listed rail/flight time. Owner decision 2026-11-08: none. */
    static final int RAIL_ACCESS_MINUTES = 0, AIR_ACCESS_MINUTES = 0;
    /** Usable from the check date (day 1) through day REVIEW_DAYS; from day REVIEW_DAYS+1 the row is expired. */
    static final int REVIEW_DAYS = 365;
    static final int MAX_BYTES = 2 * 1024 * 1024, MAX_ROWS = 5000, MAX_REPORTED_PROBLEMS = 20;
    /** A loaded table is re-checked against the file at least this often (content fingerprint, not only time/size). */
    static final long RECHECK_MILLIS = 60_000L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** File states shown to staff: OK, NOT_CONFIGURED, EMPTY, HEADER_INVALID, FORMAT_INVALID, TOO_LARGE, UNREADABLE. */
    record Row(int line, String from, String to, String mode, int minutes, BigDecimal fare, LocalDate checked, String checker, String note) {}
    record Table(List<Row> rows, List<String> problems, boolean present, String state) {
        Table(List<Row> rows, List<String> problems, boolean present) { this(rows, problems, present, present ? (rows.isEmpty() && !problems.isEmpty() ? "FORMAT_INVALID" : "OK") : "NOT_CONFIGURED"); }
        /** Line numbers and reasons only, never row contents; at most MAX_REPORTED_PROBLEMS. */
        Map<String, Object> report() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("state", state); value.put("usable_rows", rows.size()); value.put("problem_count", problems.size());
            value.put("problems", problems.subList(0, Math.min(problems.size(), MAX_REPORTED_PROBLEMS)));
            return value;
        }
    }
    record Suggestion(String status, String message, Map<String, Object> facts, Map<String, Object> table) {
        Map<String, Object> value() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status); result.put("message", message); result.put("suggestion", facts);
            result.put("table", table);
            result.put("statement", "参考表只用于预填，须由有权操作人核对后点击保存才生效；不调用任何模型。");
            return result;
        }
    }

    private static Path cachedPath; private static long cachedStamp = -1, cachedSize = -1, cachedAt = Long.MIN_VALUE;
    private static String cachedFingerprint = ""; private static LocalDate cachedDay; private static Table cached;

    static LocalDate today() { return LocalDate.now(ZONE); }

    /** Reads the reference beside the database (same data.dir). Missing file is a normal "not configured" state.
     * Re-read when the file's time or size changes, when the day changes, and at least every RECHECK_MILLIS by content. */
    static synchronized Table load() {
        String dir = System.getProperty("data.dir", "data").trim();
        Path path = Paths.get(dir.isEmpty() ? "data" : dir, FILE_NAME).toAbsolutePath().normalize();
        LocalDate day = today(); long now = System.currentTimeMillis();
        try {
            if (!Files.isRegularFile(path)) return new Table(List.of(), List.of(), false, "NOT_CONFIGURED");
            long stamp = Files.getLastModifiedTime(path).toMillis(), size = Files.size(path);
            boolean same = path.equals(cachedPath) && stamp == cachedStamp && size == cachedSize && day.equals(cachedDay);
            if (same && now - cachedAt < RECHECK_MILLIS) return cached;
            if (size > MAX_BYTES) return new Table(List.of(), List.of("参考表超过 2MB，请拆分或精简"), true, "TOO_LARGE");
            byte[] bytes = Files.readAllBytes(path);
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!(path.equals(cachedPath) && fingerprint.equals(cachedFingerprint) && day.equals(cachedDay))) cached = parse(decode(bytes), day);
            cachedPath = path; cachedStamp = stamp; cachedSize = size; cachedFingerprint = fingerprint; cachedDay = day; cachedAt = now;
            return cached;
        } catch (Exception unreadable) {
            return new Table(List.of(), List.of("参考表无法读取，请检查文件"), true, "UNREADABLE");
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

    static Table parse(String csv) { return parse(csv, today()); }

    /** Strict parse: bad rows are skipped and reported with their physical line number, never guessed. */
    static Table parse(String csv, LocalDate today) {
        Records read = records(csv);
        if (read.fatal() != null) return new Table(List.of(), List.of(read.fatal()), true, "FORMAT_INVALID");
        List<Record> records = read.records();
        List<String> problems = new ArrayList<>(); List<Row> rows = new ArrayList<>();
        if (records.isEmpty()) return new Table(List.of(), List.of("参考表为空"), true, "EMPTY");
        Record first = records.get(0);
        List<String> header = first.cells().stream().map(String::strip).toList();
        if (first.error() != null || !header.equals(HEADER)) return new Table(List.of(), List.of("表头须为：" + String.join(",", HEADER)), true, "HEADER_INVALID");
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < records.size(); i++) {
            Record rec = records.get(i); List<String> r = rec.cells(); int line = rec.line();
            if (rec.error() == null && r.stream().allMatch(String::isBlank)) continue;
            if (rows.size() >= MAX_ROWS) { problems.add("超过 " + MAX_ROWS + " 行，其余未读取"); break; }
            if (rec.error() != null) { problems.add("第 " + line + " 行" + rec.error()); continue; }
            if (r.size() != HEADER.size()) { problems.add("第 " + line + " 行列数不对"); continue; }
            String from = r.get(0).strip(), to = r.get(1).strip(), modeText = r.get(2).strip();
            String mode = switch (modeText) { case "高铁" -> "RAIL"; case "飞机" -> "AIR"; default -> ""; };
            if (city(from).isEmpty() || city(to).isEmpty() || from.length() > 64 || to.length() > 64) { problems.add("第 " + line + " 行城市为空或过长"); continue; }
            if (city(from).equals(city(to))) { problems.add("第 " + line + " 行为同城，无需填写"); continue; }
            if (mode.isEmpty()) { problems.add("第 " + line + " 行交通方式须为“高铁”或“飞机”"); continue; }
            Integer minutes = wholeNumber(r.get(3).strip());
            if (minutes == null || minutes < 1 || minutes > 2880) { problems.add("第 " + line + " 行单程时长须为 1 至 2880 的整数分钟"); continue; }
            BigDecimal fare = null; String fareText = r.get(4).strip();
            if (!fareText.isEmpty()) { if (!fareText.matches("[0-9]{1,7}(?:\\.[0-9]{1,2})?")) { problems.add("第 " + line + " 行票价须为金额或留空"); continue; } fare = new BigDecimal(fareText); }
            LocalDate checked;
            try { checked = LocalDate.parse(r.get(5).strip().replace('/', '-')); } catch (DateTimeException bad) { problems.add("第 " + line + " 行核对日期须为 2026-11-06 这样的格式"); continue; }
            if (checked.isAfter(today)) { problems.add("第 " + line + " 行核对日期晚于今天，未采用"); continue; }
            String checker = r.get(6).strip();
            if (checker.isEmpty() || checker.length() > 64) { problems.add("第 " + line + " 行须填写核对人"); continue; }
            if (!seen.add(city(from) + "|" + city(to) + "|" + mode)) { problems.add("第 " + line + " 行与前面的同一城市对、同一交通方式重复，未采用"); continue; }
            String note = r.get(7).strip(); if (note.length() > 200) note = note.substring(0, 200);
            rows.add(new Row(line, from, to, mode, minutes, fare, checked, checker, note));
        }
        return new Table(List.copyOf(rows), List.copyOf(problems), true, !rows.isEmpty() ? "OK" : problems.isEmpty() ? "EMPTY" : "FORMAT_INVALID");
    }

    /** City names compare without spaces and a trailing “市”, so “杭州” and “杭州市” match. */
    static String city(String raw) {
        String value = raw == null ? "" : raw.replaceAll("\\s+", "");
        return value.endsWith("市") && value.length() > 1 ? value.substring(0, value.length() - 1) : value;
    }

    /** Usable on today: checked on or before today, and today is no later than day REVIEW_DAYS. */
    static boolean current(Row row, LocalDate today) { return !row.checked().isAfter(today) && today.isBefore(row.checked().plusDays(REVIEW_DAYS)); }

    /**
     * departureCity: the actual departure city for this assessment (the host passes the city staff entered, otherwise the
     * teacher's base city). Forward rows come first; if none is usable, a usable opposite-direction row is offered as a
     * marked reference (FOUND_REVERSE).
     */
    static Suggestion suggest(Table table, String departureCity, String destinationCity, LocalDate today) {
        Map<String, Object> report = table.report();
        String from = city(departureCity), to = city(destinationCity);
        if (to.isEmpty()) return new Suggestion("DESTINATION_REQUIRED", "请先填写授课城市。", null, report);
        if (from.isEmpty()) return new Suggestion("BASE_CITY_UNKNOWN", "没有出发城市：请填写本次出发城市，或先补充教师档案中的常驻城市；也可手工填写。", null, report);
        if (from.equals(to)) {
            Map<String, Object> facts = facts("LOCAL", departureCity.strip(), 0, 0, null, today.plusDays(REVIEW_DAYS),
                    "按交通参考规则预填：出发城市与授课城市同为" + to + "，同城不留路上时间。");
            return new Suggestion("SAME_CITY", "同城：已预填本地出行、不留路上时间。请核对后点击保存。", facts, report);
        }
        if (!table.present()) return new Suggestion("NOT_CONFIGURED", "服务器上还没有交通参考表，请手工填写出行信息。", null, report);
        if (!"OK".equals(table.state())) return new Suggestion("TABLE_INVALID", "交通参考表当前无法使用（" + stateText(table.state()) + "），请联系管理员修正文件；本次请手工填写。", null, report);
        List<Row> forward = new ArrayList<>(), reverse = new ArrayList<>();
        for (Row row : table.rows()) {
            if (city(row.from()).equals(from) && city(row.to()).equals(to)) forward.add(row);
            else if (city(row.from()).equals(to) && city(row.to()).equals(from)) reverse.add(row);
        }
        List<Row> usable = forward.stream().filter(r -> current(r, today)).toList();
        boolean opposite = false;
        if (usable.isEmpty()) { usable = reverse.stream().filter(r -> current(r, today)).toList(); opposite = !usable.isEmpty(); }
        if (usable.isEmpty()) {
            if (forward.isEmpty() && reverse.isEmpty()) return new Suggestion("NOT_FOUND", "参考表中没有“" + from + "→" + to + "”，请手工填写或先补充参考表。", null, report);
            return new Suggestion("EXPIRED", "参考表中“" + from + "→" + to + "”的记录已超过 " + REVIEW_DAYS + " 天（一年）有效期，请先更新参考表再预填。", null, report);
        }
        Row chosen = choose(usable);
        int access = chosen.mode().equals("RAIL") ? RAIL_ACCESS_MINUTES : AIR_ACCESS_MINUTES;
        String modeName = chosen.mode().equals("RAIL") ? "高铁" : "飞机";
        String direction = opposite ? "【反方向参考：表中只有" + to + "→" + from + "，时长和票价按反方向填入，请重点核对】" : "";
        String note = direction + "按交通参考表预填（第 " + chosen.line() + " 行）：" + from + "→" + to + " " + modeName + "约 "
                + chosen.minutes() + " 分钟" + (access > 0 ? "，另加 " + access + " 分钟" : "") + "；参考表核对于 " + chosen.checked() + "（核对人 " + chosen.checker() + "）"
                + (chosen.note().isEmpty() ? "" : "；备注：" + chosen.note()) + "。";
        Map<String, Object> facts = facts(chosen.mode(), departureCity.strip(), chosen.minutes(), access, chosen.fare(), chosen.checked().plusDays(REVIEW_DAYS), note);
        if (opposite) return new Suggestion("FOUND_REVERSE", "反方向参考：表中只有“" + to + "→" + from + "”，已按其预填 " + modeName + " 出行信息，请重点核对后再点击保存。服务意愿和完整档期仍需单独核实。", facts, report);
        return new Suggestion("FOUND", "已按参考表预填 " + modeName + " 出行信息，请核对后点击保存。服务意愿和完整档期仍需单独核实。", facts, report);
    }

    private static String stateText(String state) {
        return switch (state) { case "EMPTY" -> "还没有任何参考行"; case "HEADER_INVALID" -> "表头不对"; case "TOO_LARGE" -> "文件超过 2MB"; case "UNREADABLE" -> "文件无法读取"; default -> "没有可用的行"; };
    }

    /** Owner's principle: high-speed rail within 3 hours first; otherwise air within 2.5 hours; otherwise rail if listed, else air. */
    static Row choose(List<Row> rows) {
        Comparator<Row> shortest = Comparator.comparingInt(Row::minutes);
        Optional<Row> rail = rows.stream().filter(r -> r.mode().equals("RAIL")).min(shortest), air = rows.stream().filter(r -> r.mode().equals("AIR")).min(shortest);
        if (rail.isPresent() && rail.get().minutes() <= 180) return rail.get();
        if (air.isPresent() && air.get().minutes() <= 150) return air.get();
        return rail.orElseGet(air::get);
    }

    /** Keys match the staff form's travel fields; door to door = listed time (+ access, now 0), used as both buffers. */
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

    /** One CSV record: its first physical line, its cells, and a structural error (or null). */
    record Record(int line, List<String> cells, String error) {}
    /** fatal: the file cannot be split into records reliably (an unclosed quote). */
    record Records(List<Record> records, String fatal) {}

    /**
     * Strict RFC 4180 reader: commas, CRLF/LF, quoted fields with doubled quotes and line breaks. A quote may only open a
     * field; after a closing quote only a comma or line end may follow. Such errors reject that record; an unclosed quote
     * at the end of the file rejects the whole file. Line numbers are physical lines of the file.
     */
    static Records records(String csv) {
        List<Record> out = new ArrayList<>(); List<String> row = new ArrayList<>(); StringBuilder cell = new StringBuilder();
        boolean quoted = false, closed = false; String error = null; int line = 1, start = 1, quoteLine = 1;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') { if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') { cell.append('"'); i++; } else { quoted = false; closed = true; } }
                else { if (c == '\n' || c == '\r' && !(i + 1 < csv.length() && csv.charAt(i + 1) == '\n')) line++; cell.append(c); }
                continue;
            }
            if (c == ',') { row.add(cell.toString()); cell.setLength(0); closed = false; continue; }
            if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0); out.add(new Record(start, row, error));
                row = new ArrayList<>(); closed = false; error = null; line++; start = line;
                continue;
            }
            if (closed) { if (error == null) error = "引号格式错误：结束引号后还有其他字符"; cell.append(c); continue; }
            if (c == '"') {
                if (cell.length() == 0) { quoted = true; quoteLine = line; }
                else { if (error == null) error = "引号格式错误：引号只能出现在单元格开头"; cell.append(c); }
                continue;
            }
            cell.append(c);
        }
        if (quoted) return new Records(List.of(), "第 " + quoteLine + " 行引号没有闭合，整个文件无法可靠读取，请修正后再用");
        if (cell.length() > 0 || !row.isEmpty() || closed) { row.add(cell.toString()); out.add(new Record(start, row, error)); }
        return new Records(out, null);
    }
}
