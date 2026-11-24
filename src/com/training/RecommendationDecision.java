package com.training;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** Read-only decision facts. Text similarity never creates an authorization or an assignment. */
final class RecommendationDecision {
    enum State { PASS, PENDING, EXCLUDED }
    enum Confirmation { CONFIRMED, UNKNOWN, DECLINED }
    record Request(String topic, String courseCode, String date, String start, String end, String mode, List<String> cities) {
        Request {
            topic = bounded(topic, 500, false); courseCode = bounded(courseCode, 64, true);
            date = bounded(date, 10, true); start = bounded(start, 5, true); end = bounded(end, 5, true);
            if (!date.isEmpty() && !validDate(date)) throw invalid("日期格式无效");
            if (start.isEmpty() != end.isEmpty() || !start.isEmpty() && (date.isEmpty() || !start.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]") || !end.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]") || start.compareTo(end) >= 0)) throw invalid("请明确填写同日开始和结束时间");
            if (!courseCode.isEmpty() && date.isEmpty()) throw invalid("标准课程核验需要明确日期");
            if (!Set.of("ONLINE", "ONSITE", "UNKNOWN").contains(mode)) throw invalid("授课形式无效");
            cities = List.copyOf(cities); if (cities.size() > 50 || new HashSet<>(cities).size() != cities.size()) throw invalid("城市限制重复或过多");
            for (String city : cities) bounded(city, 64, false);
        }
        Map<String,Object> value() { return map("topic", topic, "courseCode", courseCode, "date", date, "start", start, "end", end, "mode", mode, "cities", cities); }
    }
    record Evidence(String id, String candidateId, String kind, String title, String sourceRef, String sourceVersion, String text) {
        Evidence {
            if (!id.matches("ev:[a-f0-9]{64}") || !candidateId.matches("teacher:[1-9][0-9]*")) throw invalid("证据标识无效");
            if (!Set.of("PROFILE_TOPIC", "HISTORY_TOPIC").contains(kind)) throw invalid("主题证据类型无效");
            title = bounded(title, 200, false); sourceRef = bounded(sourceRef, 200, false); sourceVersion = bounded(sourceVersion, 128, false); text = bounded(text, 1600, false);
        }
        Map<String,Object> value() { return map("evidenceId", id, "candidateId", candidateId, "kind", kind, "title", title, "sourceRef", sourceRef, "sourceVersion", sourceVersion, "text", text, "verifiedQualification", false); }
    }
    record Booking(String date, String start, String end, String status) {}
    record Candidate(long teacherId, String name, String status, String baseCity, List<String> qualificationCodes,
                     Confirmation service, Confirmation availability, List<Booking> bookings, List<Evidence> evidence) {
        Candidate {
            if (teacherId < 1 || teacherId > 9007199254740991L) throw invalid("候选编号无效");
            name = bounded(name, 200, false); status = bounded(status, 32, true); baseCity = bounded(baseCity, 64, true);
            qualificationCodes = List.copyOf(qualificationCodes); bookings = List.copyOf(bookings); evidence = List.copyOf(evidence);
            Objects.requireNonNull(service); Objects.requireNonNull(availability);
            if (bookings.size() > 10000 || evidence.size() > 64) throw invalid("候选资料超出上限");
            Set<String> ids = new HashSet<>();
            for (Evidence e : evidence) if (!e.candidateId().equals("teacher:" + teacherId) || !ids.add(e.id())) throw invalid("候选证据归属或唯一性无效");
        }
        String id() { return "teacher:" + teacherId; }
    }
    record Snapshot(String organizationCode, String snapshotId, Request request, List<Candidate> candidates, boolean synthetic) {
        Snapshot {
            organizationCode = bounded(organizationCode, 128, false);
            if (!snapshotId.matches("[a-f0-9]{64}")) throw invalid("快照版本无效");
            candidates = List.copyOf(candidates); Objects.requireNonNull(request);
            if (candidates.size() > 500 || candidates.stream().map(Candidate::teacherId).distinct().count() != candidates.size()) throw invalid("候选重复或超过500位");
        }
    }
    record Check(String code, State state, String message) {
        Map<String,Object> value() { return map("code", code, "state", state.name(), "message", message); }
    }
    record Decision(Candidate candidate, State state, List<Check> checks, List<String> matchedEvidenceIds) {
        Decision { checks = List.copyOf(checks); matchedEvidenceIds = List.copyOf(matchedEvidenceIds); }
        Map<String,Object> value() {
            List<String> pending = checks.stream().filter(c -> c.state == State.PENDING).map(Check::message).toList();
            return map("candidateId", candidate.id(), "teacherId", candidate.teacherId(), "name", candidate.name(),
                    "decision", state == State.PASS ? "READY_FOR_REVIEW" : state.name(), "checks", checks.stream().map(Check::value).toList(),
                    "evidenceIds", matchedEvidenceIds, "evidence", candidate.evidence().stream().map(Evidence::value).toList(),
                    "reason", matchedEvidenceIds.isEmpty() ? "现有主题资料需要人工核对；没有记录不代表没有能力。" : "原资料含本次主题词项，仅为内容线索，请人工核对相关性。",
                    "reasonSource", "RULES", "missingInfo", pending,
                    "topicReview", matchedEvidenceIds.isEmpty() ? List.of("主题对应证据待人工核对；不以记录缺失判断能力") : List.of(), "assignmentCreated", false);
        }
    }
    static List<Decision> decide(Snapshot snapshot) {
        List<Decision> result = new ArrayList<>(); Request r = snapshot.request();
        for (Candidate c : snapshot.candidates()) {
            List<Check> checks = new ArrayList<>();
            checks.add(new Check("ACTIVE_RECORD", "在库".equals(c.status()) ? State.PASS : "出库".equals(c.status()) ? State.EXCLUDED : State.PENDING,
                    "在库".equals(c.status()) ? "当前档案在库；不等于取得课程资格" : "出库".equals(c.status()) ? "当前档案已出库" : "当前档案状态待确认"));
            if (r.courseCode().isEmpty()) checks.add(pending("COURSE_NOT_SELECTED", "手工课程尚未核验逐课资格，需确认适用资格要求"));
            else if (c.qualificationCodes().isEmpty()) checks.add(new Check("COURSE_QUALIFIED", State.PASS, "本次具体课程资格通过服务端核验"));
            else for (String code : c.qualificationCodes()) {
                boolean excluded = Set.of("NOT_CERTIFIED", "CERTIFICATION_REVOKED", "NOT_YET_VALID", "EXPIRED", "LEVEL_NOT_ALLOWED", "CITY_NOT_ALLOWED", "TEACHER_NOT_ACTIVE", "COURSE_INACTIVE").contains(code);
                checks.add(new Check(code, excluded ? State.EXCLUDED : State.PENDING, qualificationMessage(code)));
            }
            if (!r.cities().isEmpty()) {
                checks.add(c.baseCity().isEmpty() ? pending("BASE_CITY_UNKNOWN", "常驻城市未提供，城市限制待核对") :
                        new Check("BASE_CITY_LIMIT", r.cities().contains(c.baseCity()) ? State.PASS : State.EXCLUDED,
                                r.cities().contains(c.baseCity()) ? "常驻城市符合本次明确限制；不代表可出差" : "常驻城市不在本次明确限制中"));
            }
            if (r.mode().equals("UNKNOWN")) checks.add(pending("MODE_UNKNOWN", "授课形式待确认"));
            checks.add(new Check("SERVICE_CONFIRMATION", c.service() == Confirmation.CONFIRMED ? State.PASS : c.service() == Confirmation.DECLINED ? State.EXCLUDED : State.PENDING,
                    c.service() == Confirmation.CONFIRMED ? "本次服务条件已有明确确认" : c.service() == Confirmation.DECLINED ? "已明确不能承接本次服务条件" : "本次授课形式、服务区域或出差意愿待教师确认"));
            boolean conflict = false, uncertainBooking = false;
            for (Booking booking : c.bookings()) {
                if (!Set.of("已确认", "已完成", "待发送", "已发送").contains(booking.status())) continue;
                if (!validDate(booking.date())) { uncertainBooking = true; continue; }
                if (!r.date().equals(booking.date())) continue;
                boolean exact = validTime(booking.start()) && validTime(booking.end()) && booking.start().compareTo(booking.end()) < 0 && !r.start().isEmpty();
                if (!exact || !Set.of("已确认", "已完成").contains(booking.status())) { uncertainBooking = true; continue; }
                if (r.start().compareTo(booking.end()) < 0 && booking.start().compareTo(r.end()) < 0) conflict = true;
            }
            if (conflict) checks.add(new Check("SCHEDULE_CONFLICT", State.EXCLUDED, "已确认系统授课时段与本次明确时段重叠（其他业务详情不披露）"));
            else if (c.availability() == Confirmation.DECLINED) checks.add(new Check("AVAILABILITY_DECLINED", State.EXCLUDED, "本次时段已明确不可用"));
            else if (r.date().isEmpty() || r.start().isEmpty() || uncertainBooking || c.availability() != Confirmation.CONFIRMED)
                checks.add(pending("AVAILABILITY_UNKNOWN", "当前记录不能证明本次完整空闲，需核对档期和前后行程"));
            else checks.add(new Check("AVAILABILITY_CONFIRMED", State.PASS, "本次完整时段已明确确认，仍不自动派课"));
            List<String> matched = c.evidence().stream().filter(e -> matchesTopic(e.text(),r.topic())).map(Evidence::id).toList();
            State state = checks.stream().anyMatch(v -> v.state == State.EXCLUDED) ? State.EXCLUDED : checks.stream().anyMatch(v -> v.state == State.PENDING) ? State.PENDING : State.PASS;
            result.add(new Decision(c, state, checks, matched));
        }
        result.sort(Comparator.comparingLong(v -> v.candidate().teacherId())); // No invented weights or count-based ranking.
        return List.copyOf(result);
    }
    /** Exact textual clues only; shared by excerpt selection and advice, never a qualification or rank. */
    static List<String> topicTerms(String topic) {
        Set<String> terms=new LinkedHashSet<>();
        for(String term:topic.split("[\\s,，。；;、/]+")) if(term.length()>=2 && term.length()<=60) terms.add(term);
        return List.copyOf(terms);
    }
    static boolean matchesTopic(String text,String topic) { return topicTerms(topic).stream().anyMatch(text::contains); }
    private static boolean validTime(String value) { return value != null && value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"); }
    static boolean validDate(String value) {
        try { return value != null && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") && !value.startsWith("0000") && LocalDate.parse(value).toString().equals(value); }
        catch (java.time.format.DateTimeParseException invalid) { return false; }
    }
    private static Check pending(String code, String message) { return new Check(code, State.PENDING, message); }
    private static String qualificationMessage(String code) {
        return switch (code) {
            case "CERTIFICATION_MISSING" -> "没有本课程认证记录，资格待确认，不表示没有能力";
            case "CERTIFICATION_UNKNOWN" -> "本课程认证状态待确认";
            case "NOT_CERTIFIED" -> "本课程已有明确未认证记录";
            case "CERTIFICATION_REVOKED" -> "本课程认证已撤销";
            case "NOT_YET_VALID" -> "本课程认证尚未生效";
            case "EXPIRED" -> "本课程认证已过期";
            case "LEVEL_NOT_ALLOWED" -> "当前等级不符合本次明确限制";
            case "CITY_NOT_ALLOWED" -> "当前城市不符合本次明确限制";
            case "TEACHER_NOT_ACTIVE" -> "当前师资不在库";
            case "COURSE_INACTIVE" -> "所选课程当前停用";
            default -> "具体课程资格或相关资料待核实（" + bounded(code, 64, false) + "）";
        };
    }
    static Evidence evidence(String org, long teacher, String kind, String title, String ref, String version, String text) {
        String candidate = "teacher:" + teacher;
        return new Evidence("ev:" + hash(Json.write(map("org", org, "candidate", candidate, "kind", kind, "ref", ref, "version", version, "text", text))), candidate, kind, title, ref, version, text);
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static String bounded(String value, int maximum, boolean empty) {
        if (value == null || value.length() > maximum || !empty && value.isBlank() || value.indexOf('\u0000') >= 0) throw invalid("字段缺失或超出范围");
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)) { if(i+1>=value.length() || !Character.isLowSurrogate(value.charAt(++i))) throw invalid("文字含不完整Unicode字符"); }
            else if(Character.isLowSurrogate(c)) throw invalid("文字含不完整Unicode字符");
        }
        return value;
    }
    static IllegalArgumentException invalid(String value) { return new IllegalArgumentException(value); }
    static Map<String,Object> map(Object... fields) { Map<String,Object> m = new LinkedHashMap<>(); for (int i=0;i<fields.length;i+=2) m.put((String)fields[i], fields[i+1]); return m; }
}
