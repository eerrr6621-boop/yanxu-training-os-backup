package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.training.RecommendationDecision.*;

/** Additive scoped read model. No DDL, business writes, real provider configuration or dispatch. */
public final class RecommendationDecisionHost {
    private RecommendationDecisionHost() {}
    private static final RecommendationModelRunner RUNNER = new RecommendationModelRunner();
    private static final RecommendationCpaAdvisor CPA = RecommendationCpaAdvisor.configured();
    private static final LinkedHashMap<String,Receipt> RECEIPTS = new LinkedHashMap<>();
    private static final long TTL = 10 * 60 * 1000L;
    private record Input(long scope, long version, Request request) {}
    private record Receipt(Auth.Session owner, Input input, String snapshot, long created, String json) {}

    public static boolean matches(String path) { return path!=null && (path.equals("/api/teacher-recommendations/decision") || path.startsWith("/api/teacher-recommendations/decision/")); }
    /** Mount as an Api.handle branch outside MUTATION_LOCK, before generic body parsing/routing. */
    public static void handle(HttpExchange exchange) throws Exception {
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        Auth.Session session;
        synchronized(Api.MUTATION_LOCK) { session=Auth.get(Api.token(exchange)); live(session); }
        String path=exchange.getRequestURI().getPath(), method=exchange.getRequestMethod();
        if(path.equals("/api/teacher-recommendations/decision/v2")||path.startsWith("/api/teacher-recommendations/decision/v2/")){handleV2(exchange,session);return;}
        if(Set.of("/api/teacher-recommendations/decision","/api/teacher-recommendations/decision/selection").contains(path))
            throw failure(410,"旧版推荐核对接口已停用；请使用师资页“按本次需求核对师资”（/api/teacher-recommendations/decision/v2）。历史记录保留，不再返回旧版结果。");
        boolean selection=path.equals("/api/teacher-recommendations/decision/selection");
        if(!selection && !path.equals("/api/teacher-recommendations/decision")) throw failure(404,"推荐接口不存在");
        if(!selection && (method.equals("GET") || method.equals("HEAD"))) {
            String query=exchange.getRequestURI().getRawQuery();
            if(query==null || !query.matches("analysis_id=[0-9a-f-]{36}")) throw failure(400,"请提供唯一的分析编号");
            synchronized(Api.MUTATION_LOCK) { Api.ok(exchange,read(session,query.substring(12))); }
            return;
        }
        if(!method.equals("POST")) throw failure(405,"本接口不支持该请求方法");
        if(exchange.getRequestURI().getRawQuery()!=null) throw failure(400,"本接口不接受查询参数");
        String type=exchange.getRequestHeaders().getFirst("Content-Type");
        if(type==null || !type.split(";",2)[0].trim().equalsIgnoreCase("application/json")) throw failure(415,"请求须使用JSON格式");
        Map<String,Object> body=parseBody(exchange.getRequestBody().readNBytes(32769));
        if(selection) {
            if(!body.keySet().equals(Set.of("analysis_id","candidate_id"))) throw failure(400,"备选参数字段无效");
            synchronized(Api.MUTATION_LOCK) { Api.ok(exchange,select(session,string(body.get("analysis_id")),string(body.get("candidate_id")))); }
        } else {
            Map<String,Object> result=analyze(session,body);
            synchronized(Api.MUTATION_LOCK) { Api.ok(exchange,read(session,(String)result.get("analysisId"))); }
        }
    }
    /** Numeric fields use canonical safe integers at the raw-token boundary, before legacy Double parsing. */
    public static Map<String,Object> parseBody(byte[] bytes) throws Api.ApiException {
        if(bytes==null || bytes.length>32768) throw failure(413,"推荐请求内容过大");
        try {
            String raw=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            boolean quoted=false;
            for(int i=0;i<raw.length();i++) {
                char c=raw.charAt(i);
                if(quoted) { if(c=='\\') i++; else if(c=='"') quoted=false; continue; }
                if(c=='"') { quoted=true; continue; }
                if(c=='-' || c>='0' && c<='9') {
                    int start=i; while(i+1<raw.length() && "0123456789.eE+-".indexOf(raw.charAt(i+1))>=0) i++;
                    String token=raw.substring(start,i+1);
                    if(!token.matches("0|[1-9][0-9]{0,15}") || Long.parseLong(token)>9007199254740991L) throw invalid("编号与版本须为原始安全整数，不能使用小数或指数");
                }
            }
            Map<String,Object> parsed=Json.parseMap(raw); validateText(parsed,0); return parsed;
        } catch(Exception invalid) { throw failure(400,"推荐请求须为有效JSON，编号与版本须使用安全整数"); }
    }
    private static void validateText(Object value,int depth) {
        if(depth>8) throw invalid("推荐参数嵌套过深");
        if(value instanceof String s) bounded(s,32768,true);
        else if(value instanceof Map<?,?> map) for(var entry:map.entrySet()) {validateText(entry.getKey(),depth+1);validateText(entry.getValue(),depth+1);}
        else if(value instanceof List<?> list) for(Object item:list) validateText(item,depth+1);
    }

    /** Api must call outside its mutation lock; capture and publish each use a fresh authorization check. */
    public static Map<String,Object> analyze(Auth.Session session, Map<String,Object> body) throws Exception {
        if (Thread.holdsLock(Api.MUTATION_LOCK)) throw failure(503,"推荐接点需要在异步计算边界外调用");
        Input input = input(body); Snapshot start;
        synchronized(Api.MUTATION_LOCK) { start=capture(session,input); }
        List<Decision> decisions=decide(start);
        RecommendationModelRunner.Advisory advisory=RUNNER.run(start,decisions);
        synchronized(Api.MUTATION_LOCK) {
            Snapshot current=capture(session,input);
            if(!current.snapshotId().equals(start.snapshotId())) throw failure(409,"候选依据已变化，请重新分析");
            List<Map<String,Object>> candidates=project(decisions,advisory);
            String id=UUID.randomUUID().toString(); long now=System.currentTimeMillis();
            Map<String,Object> result=map("schemaVersion","recommendation_decision_v1","analysisId",id,
                    "rulePolicyStatus","DISCUSSION_DRAFT",
                    "scopeId",input.scope(),"catalogVersion",input.version(),"organizationCode",start.organizationCode(),
                    "snapshotId",DispatchConfirmationGuard.opaque(start.snapshotId()),"request",input.request().value(),"candidates",candidates,"model",advisory.metadata(),
                    "status",candidates.isEmpty()?"CONFIGURATION_REQUIRED":"COMPLETE",
                    "notice",candidates.isEmpty()?"本范围暂无可见的已绑定师资，请在课程与认证中核对范围和教师绑定。":"结果供人工了解与选入备选，待确认事项不代表没有能力；不会自动派课。",
                    "configurationEntry","course-catalog","assignmentCreated",false,"expiresAt",now+TTL);
            String json=Json.write(result);
            if(json.getBytes(StandardCharsets.UTF_8).length>1048576) throw failure(413,"本次候选资料过多，请缩小目录范围后重试");
            sweep(now);
            List<String> owned=RECEIPTS.entrySet().stream().filter(e->e.getValue().owner()==session).map(Map.Entry::getKey).toList();
            for(int i=0;i<=owned.size()-4;i++) RECEIPTS.remove(owned.get(i));
            if(RECEIPTS.size()>=32) RECEIPTS.remove(RECEIPTS.keySet().iterator().next());
            RECEIPTS.put(id,new Receipt(session,input,start.snapshotId(),now,json));
            return Json.parseMap(json);
        }
    }
    static List<Map<String,Object>> project(List<Decision> decisions,RecommendationModelRunner.Advisory advisory) {
        List<Map<String,Object>> candidates=new ArrayList<>();
        for(Decision decision:decisions) {
            Map<String,Object> value=decision.value(), suggestion=advisory.suggestions().get(decision.candidate().id());
            if(suggestion!=null && decision.state()!=State.EXCLUDED) value.put("topicAdvice",suggestion);
            candidates.add(value);
        }
        return candidates;
    }
    public static Map<String,Object> read(Auth.Session session,String analysisId) throws Exception {
        synchronized(Api.MUTATION_LOCK) { return Json.parseMap(checked(session,analysisId).json()); }
    }
    public static Map<String,Object> select(Auth.Session session,String analysisId,String candidateId) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            Receipt receipt=checked(session,analysisId);
            if(candidateId==null || !candidateId.matches("teacher:[1-9][0-9]{0,15}")) throw failure(400,"候选编号格式无效");
            for(Map<String,Object> candidate:rows(Json.parseMap(receipt.json()).get("candidates"))) if(candidateId.equals(candidate.get("candidateId"))) {
                if("EXCLUDED".equals(candidate.get("decision"))) throw failure(409,"本次存在已知条件冲突，不能选入备选");
                return map("analysisId",analysisId,"snapshotId",DispatchConfirmationGuard.opaque(receipt.snapshot()),"candidateId",candidateId,
                        "status","SHORTLIST_ONLY","decision",candidate.get("decision"),"missingInfo",candidate.get("missingInfo"),
                        "message","已核对当前依据，可作为本页人工备选；仍需确认未决事项，未创建派课或审批。","assignmentCreated",false,"persisted",false);
            }
            throw failure(404,"本次结果中没有该候选");
        }
    }
    private static Receipt checked(Auth.Session session,String id) throws Exception {
        live(session); sweep(System.currentTimeMillis());
        if(id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw failure(400,"分析编号格式无效");
        Receipt receipt=RECEIPTS.get(id);
        if(receipt==null || receipt.owner()!=session) throw failure(404,"分析结果不存在或已过期，请重新分析");
        try {
            if(!capture(session,receipt.input()).snapshotId().equals(receipt.snapshot())) throw failure(409,"候选依据已变化，请重新分析");
        } catch(Exception changed) { RECEIPTS.remove(id); throw changed; }
        return receipt;
    }
    private static void sweep(long now) { RECEIPTS.entrySet().removeIf(e->now-e.getValue().created()>=TTL); }
    private static void live(Auth.Session session) throws Exception {
        if(session==null || Auth.current(session)!=session) throw failure(401,"登录会话无效或已失效");
        if(!Auth.canWrite(session)) throw failure(403,"当前账号不能执行师资推荐分析");
    }
    private static Input input(Map<String,Object> body) throws Exception {
        try {
            if(body==null || !body.keySet().equals(Set.of("scope_id","expected_version","topic","course_code","date","start_time","end_time","mode","allowed_base_cities"))) throw invalid("推荐参数字段不完整或含未知字段");
            long scope=number(body.get("scope_id"),false), version=number(body.get("expected_version"),true);
            if(!(body.get("allowed_base_cities") instanceof List<?> raw)) throw invalid("城市限制须为列表");
            List<String> cities=new ArrayList<>(); for(Object item:raw) cities.add(string(item));
            return new Input(scope,version,new Request(string(body.get("topic")),string(body.get("course_code")),string(body.get("date")),string(body.get("start_time")),string(body.get("end_time")),string(body.get("mode")),cities));
        } catch(IllegalArgumentException malformed) { throw failure(400,malformed.getMessage()); }
    }
    private static Snapshot capture(Auth.Session session,Input input) throws Exception {return capture(session,input,false);}
    private static Snapshot capture(Auth.Session session,Input input,boolean v2) throws Exception {return capture(session,input,v2,null,Set.of());}
    /** Optional click-only collector. It never enters the original rules facts or snapshot digest. */
    private static Snapshot capture(Auth.Session session,Input input,boolean v2,Map<Long,List<Evidence>> advisoryEvidence,Set<Long> advisoryIds) throws Exception {
        live(session); OrganizationAccess.Person person=OrganizationAccessStore.person(session);
        if(!Db.get().getAutoCommit()) throw failure(409,"当前业务事务尚未完成，请稍后重新分析");
        Map<String,Object> scope=Db.one("SELECT scope_id,organization_code,version FROM m04_catalog_scopes WHERE scope_id=?",input.scope());
        if(scope==null) throw failure(404,"课程范围不存在，请先在课程与认证中配置");
        String org=text(scope,"organization_code");
        if(!OrganizationAccessStore.authorize(session,"catalog.read",OrganizationAccess.Action.VIEW,org).allowed()) throw failure(403,"当前账号没有本机构的课程读取权限");
        if(number(scope.get("version"),true)!=input.version()) throw failure(409,"课程范围已变化，请重新读取");
        // Reuse the existing whole-snapshot validation, including permanent binding integrity.
        CourseCatalogIntegration.readCourses(session,input.scope());
        Map<String,Object> revision=input.version()==0?null:Db.one("SELECT payload,bindings FROM m04_catalog_revisions WHERE scope_id=? AND version=?",input.scope(),input.version());
        List<Map<String,Object>> bindings=revision==null?List.of():rows(Json.parse(text(revision,"bindings")));
        if(bindings.size()>500) throw failure(413,"本范围超过500位师资，请先缩小范围");
        Map<Long,List<String>> qualification=new HashMap<>(); Object qualificationContext=null;
        if(!input.request().courseCode().isEmpty()) {
            Map<String,Object> course=CourseCatalogIntegration.readCourse(session,input.scope(),input.request().courseCode());
            if(!Boolean.TRUE.equals(course.get("active"))) throw failure(409,"所选标准课程当前未启用");
            Map<String,Object> q=CourseCatalogIntegration.qualification(session,input.scope(),input.version(),map("course_code",input.request().courseCode(),"as_of",input.request().date(),"accepted_levels",List.of(),"allowed_cities",List.of()));
            if(!"READY".equals(q.get("status"))) throw failure(409,"当前课程资格依据无法核实");
            qualificationContext=q.get("qualification_context");
            for(String group:List.of("eligible","gaps")) for(Map<String,Object> row:rows(q.get(group))) {
                List<String> codes=rows(row.get("reasons")).stream().map(v->text(v,"code")).toList();
                if(!Boolean.TRUE.equals(row.get("eligible")) && codes.isEmpty()) codes=List.of("CERTIFICATION_UNKNOWN");
                qualification.put(number(row.get("teacher_id"),false),codes);
            }
        }
        List<Candidate> candidates=new ArrayList<>(); List<Object> facts=new ArrayList<>();
        int totalBookings=0; long totalCharacters=0;
        bindings.sort(Comparator.comparingLong(v->number(v.get("teacher_id"),false)));
        Map<Long,Map<String,Object>> allTeachers=new HashMap<>();Map<Long,List<Map<String,Object>>> allProfiles=new HashMap<>(),allSchedules=new HashMap<>();
        if(v2&&!bindings.isEmpty()){
            String ids=bindings.stream().map(b->Long.toString(number(b.get("teacher_id"),false))).collect(java.util.stream.Collectors.joining(","));
            for(Map<String,Object> t:Db.query("SELECT id,name,status,base_city,field,intro FROM teachers WHERE id IN ("+ids+")"))allTeachers.put(number(t.get("id"),false),t);
            for(Map<String,Object> p:Db.query("SELECT id,teacher_id,LEFT(manual_profile,16000) AS manual_profile,LEFT(extracted_text,16000) AS extracted_text,updated_at FROM teacher_resumes WHERE is_current=TRUE AND teacher_id IN ("+ids+")"))allProfiles.computeIfAbsent(number(p.get("teacher_id"),false),k->new ArrayList<>()).add(p);
            for(Map<String,Object> b:v2Bookings(ids,bindings,input.request().date()))allSchedules.computeIfAbsent(number(b.get("teacher_id"),false),k->new ArrayList<>()).add(b);
        }

        for(Map<String,Object> binding:bindings) {
            long tid=number(binding.get("teacher_id"),false);
            Map<String,Object> teacher=v2?allTeachers.get(tid):Db.one("SELECT id,name,status,base_city,field,intro FROM teachers WHERE id=?",tid);
            if(teacher==null) throw failure(409,"绑定师资档案缺失，请核对原目录");
            if(WorkflowReadAccessBridge.listRows("teachers",List.of(teacher),session,Map.of()).isEmpty()) continue;
            List<Map<String,Object>> profiles=v2?allProfiles.getOrDefault(tid,List.of()):Db.query("SELECT id,manual_profile,extracted_text,updated_at FROM teacher_resumes WHERE teacher_id=? AND is_current=TRUE",tid);
            if(profiles.size()>1) throw failure(409,"当前师资资料版本不唯一，请核对档案");
            List<Evidence> evidence=new ArrayList<>();
            List<Evidence> recalled=advisoryEvidence!=null&&advisoryIds.contains(tid)?new ArrayList<>():null;
            if(recalled!=null)advisoryEvidence.put(tid,recalled);
            Map<String,Object> profile=profiles.isEmpty()?Map.of():profiles.get(0);
            String manual=text(profile,"manual_profile"), effective=manual.isBlank()?text(profile,"extracted_text")+"\n"+text(teacher,"field")+"\n"+text(teacher,"intro"):manual;
            totalCharacters+=effective.length();
            if(totalCharacters>(v2?8388608:262144)) throw failure(413,"本范围资料总量超出本次核验上限，请缩小范围");
            bounded(effective,262144,true);
            if(!effective.isBlank()) {
                String version=hash(Json.write(map("id",profile.get("id"),"updatedAt",profile.get("updated_at"),"effective",effective))), ref="teacher:"+tid+"/resume:"+text(profile,"id");
                int part=0;
                for(String excerpt:topicSnippets(effective,input.request().topic()))
                    evidence.add(evidence(org,tid,"PROFILE_TOPIC","当前有效资料的脱敏主题节选（人工资料优先）",ref+"/"+part++,version,excerpt));
                if(recalled!=null) {
                    // Do not merge unrelated profile fields into one teaching/negation claim.
                    if(!manual.isBlank())recallEvidence(recalled,org,tid,"PROFILE_TOPIC","当前有效人工资料（完整来源，仅本地核对）",ref+"/ai/manual",version,manual,input.request().topic(),manual.length()>=16000);
                    else {
                        String extracted=text(profile,"extracted_text");
                        recallEvidence(recalled,org,tid,"PROFILE_TOPIC","当前有效提取资料（完整来源，仅本地核对）",ref+"/ai/extracted",version,extracted,input.request().topic(),extracted.length()>=16000);
                        recallEvidence(recalled,org,tid,"PROFILE_TOPIC","当前专业领域（完整来源，仅本地核对）",ref+"/ai/field",version,text(teacher,"field"),input.request().topic(),false);
                        recallEvidence(recalled,org,tid,"PROFILE_TOPIC","当前介绍（完整来源，仅本地核对）",ref+"/ai/intro",version,text(teacher,"intro"),input.request().topic(),false);
                    }
                }
            }
            List<Map<String,Object>> schedule=v2?allSchedules.getOrDefault(tid,List.of()):Db.query("SELECT id,project_id,teacher_id,subject,teach_date,start_time,end_time,status FROM dispatches WHERE teacher_id=? AND status IN ('待发送','已发送','已确认','已完成') ORDER BY id LIMIT "+(5001-totalBookings),tid);
            totalBookings+=schedule.size();
            // V2 reads are bounded in v2Bookings (conflict rows <=5000, history <=HISTORY_PER_TEACHER per bound teacher).
            if(!v2&&totalBookings>5000) throw failure(413,"本范围档期总量超出本次核验上限，请缩小范围");
            List<Booking> bookings=new ArrayList<>();
            for(Map<String,Object> booking:schedule) {
                for(Object value:booking.values()) if(value!=null) totalCharacters+=value.toString().length();
                if(totalCharacters>(v2?8388608:262144)) throw failure(413,"本范围资料总量超出本次核验上限，请缩小范围");
                if(!v2||Set.of("已确认","已完成").contains(booking.get("status")))bookings.add(new Booking(text(booking,"teach_date"),text(booking,"start_time"),text(booking,"end_time"),text(booking,"status")));
                boolean ruleHistory=evidence.size()<3,advisoryHistory=recalled!=null;
                if(!"已完成".equals(booking.get("status")) || !ruleHistory&&!advisoryHistory || text(booking,"subject").isBlank()) continue;
                // catalog.read does not grant history access. Keep the original visibility check AND trusted same-org provenance.
                if(WorkflowReadAccessBridge.listRows("dispatches",List.of(booking),session,Map.of()).isEmpty()) continue;
                SurveySummaryImportsIntegration.Source source;
                try { source=SurveySummaryImportsIntegration.trustedSource(number(booking.get("project_id"),false)); }
                catch(Api.ApiException unsupported) { if(unsupported.code==403 || unsupported.code==409) continue; throw unsupported; }
                if(!org.equals(source.org())) continue;
                String historyVersion=hash(Json.write(map("booking",booking,"acceptedRevision",source.revision(),"workflowVersion",source.workflowVersion(),"acceptedAt",source.acceptedAt())));
                if(ruleHistory) {
                    List<String> snippets=topicSnippets(text(booking,"subject"),input.request().topic());
                    if(!snippets.isEmpty()) evidence.add(evidence(org,tid,"HISTORY_TOPIC","已完成记录的脱敏主题节选（不等于资格认证）","dispatch:"+booking.get("id"),historyVersion,snippets.get(0)));
                }
                if(advisoryHistory)recallEvidence(recalled,org,tid,"HISTORY_TOPIC","同机构已完成课程（完整来源，不等于资格认证）","dispatch:"+booking.get("id")+"/ai",historyVersion,text(booking,"subject"),input.request().topic(),false);
            }
            List<String> codes=qualification.getOrDefault(tid,List.of("CERTIFICATION_UNKNOWN"));
            Candidate candidate=new Candidate(tid,text(teacher,"name"),text(teacher,"status"),text(teacher,"base_city"),codes,Confirmation.UNKNOWN,Confirmation.UNKNOWN,bookings,evidence);
            candidates.add(candidate);
            facts.add(map("teacher",teacher,"profileVersion",hash(effective),"profileId",profile.get("id"),"profileUpdatedAt",profile.get("updated_at"),"scheduleVersion",hash(Json.write(schedule)),"qualification",codes,"evidence",evidence.stream().map(Evidence::value).toList()));
        }
        String digest=hash(Json.write(map("scope",scope,"catalogVersion",revision==null?"":hash(Json.write(revision)),"identityVersion",OrganizationAccessStore.configuration().version(),"person",person.personCode(),"account",session.uid,"request",input.request().value(),"qualificationContext",qualificationContext,"facts",facts)));
        return new Snapshot(org,digest,input.request(),candidates,false);
    }
    /** Completed-history clues kept per teacher (most recent first by id). Confirmed 2026-11-06: 50. */
    static final int HISTORY_PER_TEACHER=50;
    record ConflictDates(List<String> dates,boolean includeMissing) {}
    /** Stored teach_date values whose confirmed/completed bookings can change decide(): the request day itself,
     * plus any value decide() cannot read as a calendar date (it treats those as uncertain). Other days never matter. */
    static ConflictDates conflictRelevantDates(String requestDate,Collection<?> storedDates) {
        Set<String> dates=new LinkedHashSet<>();boolean missing=false;
        if(requestDate!=null&&!requestDate.isEmpty())dates.add(requestDate);
        for(Object stored:storedDates){if(stored==null){missing=true;continue;}String value=stored.toString();if(!RecommendationDecision.validDate(value))dates.add(value);}
        return new ConflictDates(List.copyOf(dates),missing);
    }
    /** Replaces one unbounded all-history read (which failed beyond 5000 rows) with: rows that can affect the
     * conflict check, plus each teacher's latest HISTORY_PER_TEACHER completed rows for topic clues. Same columns, id order. */
    private static List<Map<String,Object>> v2Bookings(String ids,List<Map<String,Object>> bindings,String requestDate)throws Exception {
        String columns="SELECT id,project_id,teacher_id,subject,teach_date,start_time,end_time,status FROM dispatches ";
        List<Object> stored=new ArrayList<>();
        for(Map<String,Object> row:Db.query("SELECT DISTINCT teach_date FROM dispatches WHERE teacher_id IN ("+ids+") AND status IN ('已确认','已完成')"))stored.add(row.get("teach_date"));
        ConflictDates relevant=conflictRelevantDates(requestDate,stored);
        TreeMap<Long,Map<String,Object>> rows=new TreeMap<>();
        if(!relevant.dates().isEmpty()||relevant.includeMissing()) {
            List<String> filters=new ArrayList<>();
            if(!relevant.dates().isEmpty())filters.add("teach_date IN ("+String.join(",",Collections.nCopies(relevant.dates().size(),"?"))+")");
            if(relevant.includeMissing())filters.add("teach_date IS NULL");
            List<Map<String,Object>> conflict=Db.query(columns+"WHERE teacher_id IN ("+ids+") AND status IN ('已确认','已完成') AND ("+String.join(" OR ",filters)+") ORDER BY id LIMIT 5001",relevant.dates().toArray());
            if(conflict.size()>5000)throw failure(413,"档期超出本次核对容量");
            for(Map<String,Object> row:conflict)rows.put(number(row.get("id"),false),row);
        }
        // One narrow id scan (no schema change), keeping each teacher's latest HISTORY_PER_TEACHER completed ids.
        Map<Long,ArrayDeque<Long>> latest=new HashMap<>();
        for(Map<String,Object> row:Db.query("SELECT id,teacher_id FROM dispatches WHERE teacher_id IN ("+ids+") AND status='已完成' ORDER BY id")) {
            ArrayDeque<Long> kept=latest.computeIfAbsent(number(row.get("teacher_id"),false),k->new ArrayDeque<>());
            kept.addLast(number(row.get("id"),false));if(kept.size()>HISTORY_PER_TEACHER)kept.removeFirst();
        }
        List<Long> history=new ArrayList<>();for(ArrayDeque<Long> kept:latest.values())for(Long id:kept)if(!rows.containsKey(id))history.add(id);
        for(int from=0;from<history.size();from+=500) {
            List<Long> chunk=history.subList(from,Math.min(history.size(),from+500));
            for(Map<String,Object> row:Db.query(columns+"WHERE id IN ("+String.join(",",Collections.nCopies(chunk.size(),"?"))+")",chunk.toArray()))
                rows.putIfAbsent(number(row.get("id"),false),row);
        }
        return new ArrayList<>(rows.values());
    }
    private static long number(Object value,boolean zero) {
        if(!(value instanceof Number)) throw invalid("编号或版本必须为安全整数");
        try { long n=new BigDecimal(value.toString()).longValueExact(); if(n<(zero?0:1)||n>9007199254740991L) throw invalid("编号或版本超出范围"); return n; }
        catch(ArithmeticException e) { throw invalid("编号或版本必须为整数"); }
    }
    private static String string(Object value) { if(!(value instanceof String s)) throw invalid("参数必须为文字"); return s; }
    private static int excerptEnd(String value,int start,int size) {
        int end=Math.min(value.length(),start+size);
        if(end<value.length() && end>start && Character.isHighSurrogate(value.charAt(end-1)) && Character.isLowSurrogate(value.charAt(end))) end--;
        return end;
    }
    static List<String> topicSnippets(String source,String topic) {
        String safe=source.replaceAll("(?i)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}","[邮箱]")
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)","[手机号]")
                .replaceAll("(?<!\\d)\\d{17}[0-9Xx](?!\\d)","[证件号]")
                .replaceAll("[\\p{Cntrl}]"," ").replaceAll("\\s+"," ").trim();
        Set<String> selected=new LinkedHashSet<>();
        for(String token:topicTerms(topic)) {
            int hit=safe.indexOf(token); if(hit<0) continue;
            int start=Math.max(0,hit-40); if(start>0 && Character.isLowSurrogate(safe.charAt(start))) start++;
            selected.add(safe.substring(start,excerptEnd(safe,start,180)));
            if(selected.size()==3)break;
        }
        return List.copyOf(selected);
    }
    /** Confirmation source collection only. No semantic parsing or selective excerpting of unreviewed text. */
    static List<String> advisorySnippets(String source,String ignoredTopic,boolean possiblyTruncated) {
        if(source==null||source.isBlank())return List.of();
        if(possiblyTruncated||source.length()>1600)throw new RecommendationCpaPayload.PrivacyException("完整资料超出本次人工确认容量，未截断或解释原文");
        return List.of(source);
    }
    private static void recallEvidence(List<Evidence> target,String org,long teacher,String kind,String title,String ref,String version,String source,String topic,boolean possiblyTruncated) {
        for(String complete:advisorySnippets(source,topic,possiblyTruncated)) {
            if(target.size()>=RecommendationCpaPayload.MAX_EVIDENCE)throw new RecommendationCpaPayload.PrivacyException("完整来源超过本次人工确认容量，未省略来源");
            target.add(evidence(org,teacher,kind,title,ref+"/0",version,complete));
        }
    }
    private static String text(Map<String,Object> row,String key) { Object value=row.get(key);return value==null?"":String.valueOf(value); }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value) { return new ArrayList<>((List<Map<String,Object>>)value); }
    private static Api.ApiException failure(int code,String message) { return new Api.ApiException(code,message); }
    private record V2Input(Input base,String contextType,long contextId,String place) {
        Map<String,Object> factRequest(){Request r=base.request();return map("topic",r.topic(),"course_code",r.courseCode(),"date",r.date(),"start_time",r.start(),"end_time",r.end(),"mode",r.mode(),"teaching_place",place);}
    }
    private record V2Capture(V2Input input,Snapshot snapshot,RecommendationRanking.Context rankContext,List<RecommendationRanking.Input> rankInputs,List<Decision> decisions,String privateVersion,Map<Long,RecommendationAssessmentIntegration.Snapshot> assessments,boolean canRecord,Set<Long> invitationNotices) {}
    private record V2Receipt(Auth.Session owner,List<V2Input> inputs,List<String> versions,long created,String json) {}
    private static final LinkedHashMap<String,V2Receipt> V2_CACHE=new LinkedHashMap<>();
    private static final Set<String> AI_IN_FLIGHT=new HashSet<>();
    private static final Set<String> BUSY_ORGS=new HashSet<>();private static int activeComputations=0;
    static AutoCloseable acquireV2(Set<String> organizations)throws Api.ApiException {synchronized(BUSY_ORGS){if(activeComputations>=3||organizations.stream().anyMatch(BUSY_ORGS::contains))throw failure(429,"本范围正在核对，请稍后重试；本次未排队");activeComputations++;BUSY_ORGS.addAll(organizations);return ()->{synchronized(BUSY_ORGS){activeComputations--;BUSY_ORGS.removeAll(organizations);}};}}
    private static void handleV2(HttpExchange ex,Auth.Session session)throws Exception {
        String root="/api/teacher-recommendations/decision/v2",path=ex.getRequestURI().getPath(),method=ex.getRequestMethod();
        if((method.equals("GET")||method.equals("HEAD"))&&path.equals(root)) {Map<String,String> q=Api.query(ex);if(!q.keySet().equals(Set.of("analysis_id")))throw failure(400,"请提供分析编号");Api.ok(ex,readV2(session,q.get("analysis_id")));return;}
        if((method.equals("GET")||method.equals("HEAD"))&&path.equals(root+"/contexts")){Map<String,String> q=Api.query(ex);if(!q.keySet().equals(Set.of("scope_id"))||!q.get("scope_id").matches("[1-9][0-9]{0,15}"))throw failure(400,"请选择机构课程范围");synchronized(Api.MUTATION_LOCK){Api.ok(ex,contextsV2(session,Long.parseLong(q.get("scope_id"))));}return;}
        if(!Set.of(root,root+"/batch",root+"/selection",root+"/advice",root+"/assessments/read",root+"/assessments/save").contains(path))throw failure(404,"推荐接口不存在");
        if(!method.equals("POST"))throw failure(405,"请求方法不支持");if(ex.getRequestURI().getRawQuery()!=null)throw failure(400,"此接口不接受查询参数");
        String type=ex.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.split(";",2)[0].trim().equalsIgnoreCase("application/json"))throw failure(415,"请提交JSON对象");
        Map<String,Object> b=parseBody(ex.getRequestBody().readNBytes(32769));
        if(path.endsWith("/advice"))throw failure(410,PureRulePolicy.ADVICE_DISABLED_MESSAGE); // Pure-rule policy: adviceV2 is kept for reference/tests but is no longer routed.
        if(path.endsWith("/assessments/read")||path.endsWith("/assessments/save")){synchronized(Api.MUTATION_LOCK){Api.ok(ex,assessmentV2(session,b,path.endsWith("/save")));}return;}
        if(path.endsWith("/selection")){if(!b.keySet().equals(Set.of("analysis_id","request_id","candidate_id")))throw failure(400,"备选参数无效");Api.ok(ex,selectV2(session,string(b.get("analysis_id")),string(b.get("request_id")),string(b.get("candidate_id"))));return;}
        List<Map<String,Object>> requests;
        if(path.endsWith("/batch")){if(!b.keySet().equals(Set.of("requests"))||!(b.get("requests") instanceof List<?>))throw failure(400,"请提供最多三项已获权需求");requests=rows(b.get("requests"));}else requests=List.of(b);
        try {Api.ok(ex,analyzeV2(session,requests));}catch(Api.ApiException busy){if(busy.code==429)ex.getResponseHeaders().set("Retry-After","1");throw busy;}
    }
    static Map<String,Object> contextsV2(Auth.Session s,long scope)throws Exception {
        live(s);CourseCatalogIntegration.readCourses(s,scope);Map<String,Object> row=Db.one("SELECT organization_code FROM m04_catalog_scopes WHERE scope_id=?",scope);String org=(String)row.get("organization_code");List<Map<String,Object>> contexts=new ArrayList<>();
        for(Map<String,Object> d:Db.query("SELECT d.* FROM demands d JOIN workflow_demands w ON w.demand_id=d.id WHERE w.organization_code=? ORDER BY d.id DESC LIMIT 501",org))if(!WorkflowReadAccessBridge.listRows("demands",List.of(d),s,Map.of()).isEmpty())contexts.add(map("type","DEMAND","id",d.get("id"),"label","需求 #"+d.get("id")+" "+Objects.toString(d.get("title"),Objects.toString(d.get("name"),""))));
        for(Map<String,Object> p:Db.query("SELECT p.* FROM projects p JOIN workflow_acceptances a ON a.project_id=p.id JOIN workflow_demands w ON w.demand_id=a.demand_id WHERE w.organization_code=? ORDER BY p.id DESC LIMIT 501",org))if(!WorkflowReadAccessBridge.listRows("projects",List.of(p),s,Map.of()).isEmpty())contexts.add(map("type","PROJECT","id",p.get("id"),"label","项目 #"+p.get("id")+" "+Objects.toString(p.get("title"),"")));
        contexts.addAll(LegacyOwnershipIntegration.contexts(s,org));
        if(contexts.size()>500)throw failure(413,"可见业务范围过多，请从具体项目进入核对");return map("scope_id",scope,"contexts",contexts);
    }
    private static V2Input inputV2(Map<String,Object> raw)throws Exception {
        Set<String> fields=Set.of("scope_id","expected_version","topic","course_code","date","start_time","end_time","mode","allowed_base_cities","context_type","context_id","teaching_place");if(!raw.keySet().equals(fields))throw failure(400,"本次推荐参数不完整或含未知字段");Map<String,Object> base=new LinkedHashMap<>(raw);base.remove("context_type");base.remove("context_id");base.remove("teaching_place");Input input=input(base);String type=string(raw.get("context_type")),place=bounded(string(raw.get("teaching_place")),200,true);long id=number(raw.get("context_id"),true);
        if(!Set.of("UNSAVED","DEMAND","PROJECT").contains(type)||type.equals("UNSAVED")!=(id==0))throw failure(400,"本次关联业务无效");return new V2Input(input,type,id,place);
    }
    private static String authorizeV2(Auth.Session s,V2Input in)throws Exception {return authorizeV2(s,in,true);}
    private static String authorizeV2(Auth.Session s,V2Input in,boolean checkVersion)throws Exception {
        live(s);Map<String,Object> scope=Db.one("SELECT organization_code,version FROM m04_catalog_scopes WHERE scope_id=?",in.base.scope());if(scope==null)throw failure(404,"课程范围不存在");String org=(String)scope.get("organization_code");if(!OrganizationAccessStore.authorize(s,"catalog.read",OrganizationAccess.Action.VIEW,org).allowed())throw failure(403,"当前账号没有该课程范围读取权限");if(checkVersion&&number(scope.get("version"),true)!=in.base.version())throw failure(409,"课程目录版本已变化，请刷新");
        if(!in.contextType.equals("UNSAVED"))RecommendationAssessmentIntegration.context(s,in.base.scope(),in.contextType,in.contextId,false);return org;
    }
    static Map<String,Object> assessmentV2(Auth.Session s,Map<String,Object> b,boolean save)throws Exception {
        Set<String> expected=save?Set.of("input","teacher_id","expected_revision","request_id","facts","reason"):Set.of("input","teacher_id");
        boolean reviewPresent=save&&b.containsKey("experience_review");Set<String> actual=new HashSet<>(b.keySet());if(reviewPresent)actual.remove("experience_review");
        if(!actual.equals(expected))throw failure(400,"本次核对参数无效");
        V2Input in=inputV2(objectV2(b.get("input")));authorizeV2(s,in,!save);if(in.contextType.equals("UNSAVED"))throw failure(409,"请先关联已保存且有权操作的真实需求或项目");
        var context=RecommendationAssessmentIntegration.context(s,in.base.scope(),in.contextType,in.contextId,save);long teacher=number(b.get("teacher_id"),false);
        var key=RecommendationAssessmentIntegration.key(s,context,teacher,in.factRequest(),save);
        if(save) {
            Map<String,Object> review=b.get("experience_review")==null?null:objectV2(b.get("experience_review"));
            return RecommendationAssessmentIntegration.save(s,key,number(b.get("expected_revision"),true),in.base.version(),string(b.get("request_id")),objectV2(b.get("facts")),string(b.get("reason")),reviewPresent,review,confirmed->{
                try {RecommendationExperienceReview.validateSources(confirmed,"teacher:"+teacher,experienceSources(s,in,teacher));}
                catch(RecommendationCpaPayload.PrivacyException unavailable){throw failure(409,"完整来源已变化或无法核对，请重新读取资料后确认经历");}
            });
        }
        var assessment=RecommendationAssessmentIntegration.read(s,key);Map<String,Object> view=assessment.view();
        try {
            List<Evidence> sources=experienceSources(s,in,teacher);view.put("experience_sources",sources.stream().map(Evidence::value).toList());
            String status="NONE";
            if(assessment.review()!=null) {
                status="CURRENT";
                try {RecommendationExperienceReview.validateSources(assessment.review(),"teacher:"+teacher,sources);}
                catch(RecommendationCpaPayload.PrivacyException changed){status="STALE";}
                if(assessment.facts()==null||!assessment.facts().current(java.time.Instant.now()))status="STALE";
            }
            view.put("experience_review_status",status);view.put("experience_review_reason",status.equals("STALE")?"经历确认的材料版本或核对有效期已变化，请重新核对后保存":"");
        }catch(RecommendationCpaPayload.PrivacyException unavailable){view.put("experience_sources",List.of());view.put("experience_review_status","UNAVAILABLE");view.put("experience_review_reason","完整来源超出本次确认容量，未截断或省略；仍可保存原有服务和交通事实");}
        return view;
    }
    private static List<Evidence> experienceSources(Auth.Session s,V2Input in,long teacher)throws Exception {
        Map<Long,List<Evidence>> sources=new LinkedHashMap<>();capture(s,in.base,true,sources,Set.of(teacher));
        return List.copyOf(sources.getOrDefault(teacher,List.of()));
    }
    static Map<String,Object> analyzeV2(Auth.Session s,List<Map<String,Object>> bodies)throws Exception {
        if(Thread.holdsLock(Api.MUTATION_LOCK))throw failure(503,"推荐计算须在业务写锁之外执行");if(bodies.isEmpty()||bodies.size()>3)throw failure(400,"每次可核对一至三项需求");List<V2Input> inputs=new ArrayList<>();Set<String> orgs=new HashSet<>(),unique=new HashSet<>();
        synchronized(Api.MUTATION_LOCK){for(Map<String,Object> body:bodies){V2Input in=inputV2(body);String org=authorizeV2(s,in);if(!unique.add(TrainingSummariesWorkflow.canonical(body)))throw failure(400,"同次需求不能重复");if(bodies.size()>1&&in.contextType.equals("UNSAVED"))throw failure(400,"联合建议须逐项关联已保存且已获权的需求或项目");inputs.add(in);orgs.add(org);}}
        try(AutoCloseable lease=acquireV2(Set.copyOf(orgs))) {
            List<V2Capture> captures=new ArrayList<>();synchronized(Api.MUTATION_LOCK){for(V2Input in:inputs)captures.add(captureV2(s,in));sweepV2(System.currentTimeMillis());List<String> versions=captures.stream().map(V2Capture::privateVersion).toList();for(V2Receipt cached:V2_CACHE.values())if(cached.owner()==s&&cached.inputs().equals(inputs)&&cached.versions().equals(versions)){Map<String,Object> hit=Json.parseMap(cached.json());hit.put("cacheHit",true);return withUsageV2(hit,CPA);}}
            List<Map<String,Object>> results=new ArrayList<>();List<RecommendationJointPlanner.Request> joint=new ArrayList<>();
            for(int i=0;i<captures.size();i++){V2Capture cap=captures.get(i);String requestId="request-"+(i+1);RecommendationRanking.Result rank=RecommendationRanking.rank(cap.rankContext(),cap.rankInputs());
                // Ordinary analysis never invokes the optional CPA channel; only /advice does.
                var advice=new RecommendationModelRunner.Advisory(map("source","RULES","status","NOT_REQUESTED","modelVisible",false,"realInference",false,"inferenceSucceeded",false,"message","尚未点击AI辅助比较；当前仅为规则筛选结果"),Map.of());Map<Long,Decision> byId=new LinkedHashMap<>();cap.decisions().forEach(d->byId.put(d.candidate().teacherId(),d));
                List<Map<String,Object>> candidates=new ArrayList<>();List<RecommendationRanking.Ranked> ordered=new ArrayList<>();ordered.addAll(rank.normal());ordered.addAll(rank.pending());ordered.addAll(rank.excluded());
                for(var ranked:ordered){Decision d=byId.get(ranked.teacherId());Map<String,Object> value=d.value();value.put("decision",ranked.state()==State.PASS?"READY_FOR_REVIEW":ranked.state().name());value.put("preference",ranked.tier().name());value.put("rankReasons",ranked.reasonCodes());value.put("missingInfo",mergeMessagesV2(value.get("missingInfo"),ranked.missingInfo()));value.put("assessmentRevision",cap.assessments().containsKey(ranked.teacherId())?cap.assessments().get(ranked.teacherId()).revision():0);value.put("canRecordAssessment",cap.canRecord());value.put("coordinationNotices",cap.invitationNotices().contains(ranked.teacherId())?List.of("存在其他待答复邀约，请人工协调"):List.of());
                    if(advice.suggestions().containsKey(d.candidate().id()))value.put("topicAdvice",advice.suggestions().get(d.candidate().id()));candidates.add(value);}
                results.add(map("requestId",requestId,"scopeId",cap.input().base.scope(),"catalogVersion",cap.input().base.version(),"contextType",cap.input().contextType,"contextId",cap.input().contextId,"request",cap.snapshot().request().value(),"candidates",candidates,"normalCount",rank.normal().size(),"shortage",rank.shortage(),"scanned",rank.scanned(),"model",advice.metadata(),"ruleVersion",rank.ruleVersion(),"notice","按本地、已核对的高铁/飞机可达梯度及可比距离与费用给出建议；缺资料单列，不自动派课。"));
                List<RecommendationRanking.Ranked> jointCandidates=rank.normal().stream().map(r->new RecommendationRanking.Ranked(r.teacherId(),r.candidateId(),requestId,r.state(),r.tier(),r.occupiedWindow(),r.windowBasis(),r.reasonCodes(),r.missingInfo())).toList();joint.add(new RecommendationJointPlanner.Request(requestId,jointCandidates));
            }
            var plan=RecommendationJointPlanner.solve(joint,100000);String id=UUID.randomUUID().toString();long now=System.currentTimeMillis();Map<String,Object> result=map("schemaVersion","recommendation_decision_v2","analysisId",id,"snapshotId",DispatchConfirmationGuard.opaque(id),"results",results,"joint",map("choices",plan.choices().stream().map(c->map("requestId",c.requestId(),"teacherId",c.teacherId(),"reason",c.reasonCode())).toList(),"assignedCount",plan.assignedCount(),"limited",plan.limited(),"coverageProof",plan.coverageProof().name(),"visitedNodes",plan.visitedNodes(),"assignmentCreated",false),"assignmentCreated",false,"expiresAt",now+TTL,"rulePolicyStatus","CONFIRMED_RULES_LOCAL_IMPLEMENTATION");
            String json=Json.write(result);if(json.getBytes(StandardCharsets.UTF_8).length>2097152)throw failure(413,"本次结果超过容量，请缩小范围");
            synchronized(Api.MUTATION_LOCK){for(int i=0;i<inputs.size();i++)if(!captureV2(s,inputs.get(i)).privateVersion().equals(captures.get(i).privateVersion()))throw failure(409,"候选依据已变化，请重新分析");sweepV2(now);List<String> owned=V2_CACHE.entrySet().stream().filter(e->e.getValue().owner().uid==s.uid).map(Map.Entry::getKey).toList();for(int i=0;i<=owned.size()-4;i++)V2_CACHE.remove(owned.get(i));if(V2_CACHE.size()>=64)V2_CACHE.remove(V2_CACHE.keySet().iterator().next());V2_CACHE.put(id,new V2Receipt(s,List.copyOf(inputs),captures.stream().map(V2Capture::privateVersion).toList(),now,json));}
            return withUsageV2(Json.parseMap(json),CPA);
        }
    }
    private static Map<String,Object> withUsageV2(Map<String,Object> result,RecommendationCpaAdvisor advisor){result.put("aiUsage",advisor.usageBudget());return result;}
    static Map<String,Object> readV2(Auth.Session s,String id)throws Exception {synchronized(Api.MUTATION_LOCK){live(s);sweepV2(System.currentTimeMillis());V2Receipt receipt=V2_CACHE.get(id);if(receipt==null||receipt.owner()!=s)throw failure(404,"分析不存在或已过期，请重新分析");try{for(int i=0;i<receipt.inputs.size();i++)if(!captureV2(s,receipt.inputs.get(i)).privateVersion().equals(receipt.versions.get(i)))throw failure(409,"当前依据已变化，请重新分析");}catch(Exception invalid){V2_CACHE.remove(id);throw invalid;}return withUsageV2(Json.parseMap(receipt.json),CPA);}}
    static Map<String,Object> selectV2(Auth.Session s,String id,String request,String candidate)throws Exception {synchronized(Api.MUTATION_LOCK){Map<String,Object> result=readV2(s,id);for(Map<String,Object> item:rows(result.get("results")))if(request.equals(item.get("requestId")))for(Map<String,Object> c:rows(item.get("candidates")))if(candidate.equals(c.get("candidateId"))){if("EXCLUDED".equals(c.get("decision")))throw failure(409,"本次存在已知条件不符，不能列为备选");return map("analysisId",id,"snapshotId",result.get("snapshotId"),"requestId",request,"candidateId",candidate,"status","SHORTLIST_ONLY","assignmentCreated",false,"persisted",false,"message","已重新核对当前依据，仅作为本页备选；未锁定老师、未派课。");}throw failure(404,"本次候选不存在");}}
    /** Explicit click only; neither analyze nor cache reads initiate external work. */
    static Map<String,Object> adviceV2(Auth.Session s,String id,String request,RecommendationCpaAdvisor advisor)throws Exception {
        if(Thread.holdsLock(Api.MUTATION_LOCK))throw failure(503,"AI辅助须在业务写锁之外执行");
        V2Receipt receipt;V2Capture cap;int index;Map<String,Object> original;String flight=id+":"+request;
        synchronized(Api.MUTATION_LOCK){
            original=readV2(s,id);receipt=V2_CACHE.get(id);
            if(request==null||!request.matches("request-[1-3]"))throw failure(400,"需求编号无效");index=Integer.parseInt(request.substring(8))-1;
            if(index>=receipt.inputs().size())throw failure(404,"本次分析没有该需求");
            Map<String,Object> model=objectV2(rows(original.get("results")).get(index).get("model"));
            if(advisor.version().equals(model.get("advisoryVersion")))return withUsageV2(original,advisor);
            cap=captureV2(s,receipt.inputs().get(index));
            if(!AI_IN_FLIGHT.add(flight))throw failure(429,"本次短名单已有AI请求进行中，请等待原结果");
        }
        try {
        var rank=RecommendationRanking.rank(cap.rankContext(),cap.rankInputs());
        Map<Long,Decision> indexed=new HashMap<>();cap.decisions().forEach(d->indexed.put(d.candidate().teacherId(),d));
        List<Decision> shortlist=rank.explanationShortlist().stream().map(indexed::get).toList();
        Map<Long,List<Evidence>> recalled=new LinkedHashMap<>();Map<String,Map<String,Object>> reviews=new LinkedHashMap<>();
        RecommendationModelRunner.Advisory advice;
        try {
            Set<Long> reviewedTeachers=new LinkedHashSet<>();
            for(Decision decision:shortlist) {
                var assessment=cap.assessments().get(decision.candidate().teacherId());
                if(assessment!=null&&assessment.review()!=null) {
                    if(assessment.facts()==null||!assessment.facts().current(java.time.Instant.now()))throw new RecommendationCpaPayload.PrivacyException("STALE_CONFIRMED_EXPERIENCE","经历核对已过有效期");
                    reviews.put(decision.candidate().id(),assessment.review());reviewedTeachers.add(decision.candidate().teacherId());
                }
            }
            if(!reviewedTeachers.isEmpty())synchronized(Api.MUTATION_LOCK) {
                readV2(s,id);
                V2Capture sameMaterials;
                try {sameMaterials=captureV2(s,receipt.inputs().get(index),recalled,reviewedTeachers);}
                catch(RecommendationCpaPayload.PrivacyException unavailable){throw new RecommendationCpaPayload.PrivacyException("STALE_CONFIRMED_EXPERIENCE","完整来源无法确认");}
                if(!sameMaterials.privateVersion().equals(cap.privateVersion()))throw failure(409,"候选依据已变化，请重新核对");
                for(Decision decision:shortlist)if(reviews.containsKey(decision.candidate().id())) {
                    try {RecommendationExperienceReview.validateSources(reviews.get(decision.candidate().id()),decision.candidate().id(),recalled.getOrDefault(decision.candidate().teacherId(),List.of()));}
                    catch(RecommendationCpaPayload.PrivacyException changed){throw new RecommendationCpaPayload.PrivacyException("STALE_CONFIRMED_EXPERIENCE","经历确认绑定的完整材料已变化");}
                }
            }
            // Only the local review UI sees complete source text. Payload exports saved, confirmed enums exclusively.
            Map<Long,Candidate> advisoryCandidates=new LinkedHashMap<>();
            for(Decision decision:shortlist) {
                Candidate c=decision.candidate();
                advisoryCandidates.put(c.teacherId(),new Candidate(c.teacherId(),c.name(),c.status(),c.baseCity(),c.qualificationCodes(),c.service(),c.availability(),c.bookings(),recalled.getOrDefault(c.teacherId(),List.of())));
            }
            Snapshot advisorySnapshot=new Snapshot(cap.snapshot().organizationCode(),cap.snapshot().snapshotId(),cap.snapshot().request(),cap.snapshot().candidates().stream().map(c->advisoryCandidates.getOrDefault(c.teacherId(),c)).toList(),cap.snapshot().synthetic());
            List<Decision> advisoryShortlist=shortlist.stream().map(d->new Decision(advisoryCandidates.get(d.candidate().teacherId()),d.state(),d.checks(),d.matchedEvidenceIds())).toList();
            advice=advisor.run(advisorySnapshot,advisoryShortlist,new RecommendationCpaPayload.ReviewedProjection(reviews),()->{
                synchronized(Api.MUTATION_LOCK){readV2(s,id);if(!captureV2(s,receipt.inputs().get(index)).privateVersion().equals(cap.privateVersion()))throw failure(409,"候选依据已变化，请重新核对");}
            });
        } catch(RecommendationCpaPayload.PrivacyException rejected) {advice=advisor.refused(cap.snapshot(),rejected.status());}
        synchronized(Api.MUTATION_LOCK){
            Map<String,Object> current=readV2(s,id); // Recheck live session, all contexts, material, facts, occupation and rules.
            if(!captureV2(s,receipt.inputs().get(index)).privateVersion().equals(cap.privateVersion()))throw failure(409,"候选依据已变化，请重新核对");
            Map<String,Object> result=rows(current.get("results")).get(index);result.put("model",advice.metadata());
            for(Map<String,Object> candidate:rows(result.get("candidates"))){candidate.remove("topicAdvice");Map<String,Object> suggestion=advice.suggestions().get(candidate.get("candidateId"));if(suggestion!=null&&!"EXCLUDED".equals(candidate.get("decision")))candidate.put("topicAdvice",suggestion);}
            V2_CACHE.put(id,new V2Receipt(s,receipt.inputs(),receipt.versions(),receipt.created(),Json.write(current)));
            return withUsageV2(current,advisor);
        }
        } finally {synchronized(Api.MUTATION_LOCK){AI_IN_FLIGHT.remove(flight);}}
    }
    private static V2Capture captureV2(Auth.Session s,V2Input in)throws Exception {return captureV2(s,in,null,Set.of());}
    private static V2Capture captureV2(Auth.Session s,V2Input in,Map<Long,List<Evidence>> advisoryEvidence,Set<Long> advisoryIds)throws Exception {
        String org=authorizeV2(s,in);Snapshot raw=capture(s,in.base,true,advisoryEvidence,advisoryIds);var context=in.contextType.equals("UNSAVED")?null:RecommendationAssessmentIntegration.context(s,in.base.scope(),in.contextType,in.contextId,false);Map<Long,RecommendationAssessmentIntegration.Snapshot> assessments=new LinkedHashMap<>();List<Candidate> candidates=new ArrayList<>();List<Object> versions=new ArrayList<>();Map<Long,List<Map<String,Object>>> occupation=new HashMap<>();
        Map<Long,List<Map<String,Object>>> invitations=new HashMap<>();Set<Long> invitationNotices=new HashSet<>();Set<Long> ownProjects=new HashSet<>();
        if(in.contextType.equals("PROJECT"))ownProjects.add(in.contextId);
        if(in.contextType.equals("DEMAND")) {
            for(Map<String,Object> accepted:Db.query("SELECT project_id FROM workflow_acceptances WHERE demand_id=?",in.contextId))ownProjects.add(number(accepted.get("project_id"),false));
            Map<String,Object> legacy=LegacyOwnershipIntegration.source("DEMAND",in.contextId);
            if(legacy!=null)for(Map<String,Object> project:Db.query("SELECT id FROM projects WHERE demand_id=? ORDER BY id LIMIT 501",in.contextId)) {
                long projectId=number(project.get("id"),false);Map<String,Object> source=LegacyOwnershipIntegration.source("PROJECT",projectId);
                if(source!=null&&org.equals(source.get("organization_code"))&&number(source.get("demand_id"),true)==in.contextId)ownProjects.add(projectId);
            }
        }
        if(context!=null&&!context.sourceVersion().isEmpty())versions.add(map("legacy_source_version",context.sourceVersion()));
        if(!raw.candidates().isEmpty()) {
            String ids=raw.candidates().stream().map(c->Long.toString(c.teacherId())).collect(java.util.stream.Collectors.joining(","));
            List<Map<String,Object>> sent=Db.query("SELECT id,project_id,teacher_id,teach_date,start_time,end_time,status FROM dispatches WHERE teacher_id IN ("+ids+") AND status='已发送' ORDER BY id LIMIT 5001");
            if(sent.size()>5000)throw failure(413,"待答复邀约超出本次核对容量");
            for(Map<String,Object> row:sent) {
                if(ownProjects.contains(number(row.get("project_id"),false)))continue;
                invitations.computeIfAbsent(number(row.get("teacher_id"),false),ignored->new ArrayList<>()).add(row);
                // Private snapshot only: no foreign organization, title, venue, timestamp or count is returned.
                versions.add(map("invitation",row));
            }
        }
        for(Candidate c:raw.candidates()) {var assessment=context==null?null:RecommendationAssessmentIntegration.captured(context,c.teacherId(),in.factRequest());var facts=assessment==null?null:assessment.facts();boolean current=facts!=null&&facts.current(java.time.Instant.now());if(assessment!=null){assessments.put(c.teacherId(),assessment);versions.add(map("id",c.teacherId(),"revision",assessment.revision(),"hash",assessment.internalHash(),"current",current));}
            Confirmation availability=current?facts.availability():Confirmation.UNKNOWN,service=current?facts.service():Confirmation.UNKNOWN;
            candidates.add(new Candidate(c.teacherId(),c.name(),c.status(),c.baseCity(),c.qualificationCodes(),service,availability,c.bookings(),c.evidence()));List<Map<String,Object>> booked=DispatchConfirmationGuard.bookings(c.teacherId());occupation.put(c.teacherId(),booked);List<Object> safety=new ArrayList<>();for(Map<String,Object> booking:booked){var window=DispatchConfirmationGuard.occupied(booking);safety.add(map("id",booking.get("id"),"version",window==null?null:window.internalVersion(),"status",booking.get("status")));}versions.add(safety);
        }
        String privateVersion=hash(TrainingSummariesWorkflow.canonical(map("source",raw.snapshotId(),"facts",versions,"rule",RecommendationRanking.RULE_VERSION,"request",in.factRequest(),"context_type",in.contextType,"context_id",in.contextId)));
        Snapshot snapshot=new Snapshot(org,privateVersion,in.base.request(),candidates,false);List<Decision> decisions=new ArrayList<>(decide(snapshot));String requestKey=DispatchConfirmationGuard.opaque(TrainingSummariesWorkflow.canonical(map("context",in.contextType,"id",in.contextId,"request",in.factRequest(),"scope",in.base.scope())));
        java.time.Instant begin=RecommendationAssessmentIntegration.window(in.factRequest(),true),end=RecommendationAssessmentIntegration.window(in.factRequest(),false);var window=begin==null||end==null?null:new RecommendationRanking.Window(begin,end);var rankContext=new RecommendationRanking.Context(requestKey,in.base.request().mode().equals("ONLINE"),in.place,window);
        List<RecommendationRanking.Input> ranking=new ArrayList<>();
        for(int i=0;i<decisions.size();i++){Decision d=decisions.get(i);var assessment=assessments.get(d.candidate().teacherId());var facts=assessment==null?null:assessment.facts();RecommendationRanking.TravelFacts travel=null;
            if(facts!=null&&facts.current(java.time.Instant.now())){var cost=facts.travelCost()!=null&&facts.costCoverage().equals("TOTAL_REQUEST")?new RecommendationRanking.Cost(facts.travelCost(),facts.currency(),"TRAVEL_ONLY"):null;travel=new RecommendationRanking.TravelFacts(d.candidate().teacherId(),requestKey,facts.feasibility(),RecommendationRanking.TransportMode.valueOf(facts.mode()),facts.departurePlace(),facts.teachingPlace(),facts.legMinutes(),facts.doorMinutes(),facts.beforeBuffer(),facts.afterBuffer(),facts.distanceMeters(),cost);}
            if(window!=null){int before=in.base.request().mode().equals("ONLINE")||travel==null||travel.beforeBufferMinutes()==null?0:travel.beforeBufferMinutes(),after=in.base.request().mode().equals("ONLINE")||travel==null||travel.afterBufferMinutes()==null?0:travel.afterBufferMinutes();var proposed=new DispatchConfirmationGuard.Occupied(begin.minusSeconds(before*60L),end.plusSeconds(after*60L),false,"");boolean conflict=false,unknown=false;for(Map<String,Object> booking:occupation.get(d.candidate().teacherId())){var busy=DispatchConfirmationGuard.occupied(booking);if(busy==null){if(DispatchConfirmationGuard.potentiallyRelevant(booking,proposed))unknown=true;}else if(DispatchConfirmationGuard.overlap(proposed,busy))conflict=true;}if(conflict||unknown){List<Check> checks=new ArrayList<>(d.checks());checks.add(new Check(conflict?"GLOBAL_UNAVAILABLE":"CONFIRMED_WINDOW_UNKNOWN",conflict?State.EXCLUDED:State.PENDING,conflict?"本次时段或已知出行缓冲不可用（其他业务详情不披露）":"存在无法完整核对的已确认档期，需先核对"));d=new Decision(d.candidate(),conflict?State.EXCLUDED:d.state()==State.EXCLUDED?State.EXCLUDED:State.PENDING,checks,d.matchedEvidenceIds());decisions.set(i,d);}}
            if(window!=null) {
                int before=in.base.request().mode().equals("ONLINE")||travel==null||travel.beforeBufferMinutes()==null?0:travel.beforeBufferMinutes();
                int after=in.base.request().mode().equals("ONLINE")||travel==null||travel.afterBufferMinutes()==null?0:travel.afterBufferMinutes();
                var proposed=new DispatchConfirmationGuard.Occupied(begin.minusSeconds(before*60L),end.plusSeconds(after*60L),false,"");
                for(Map<String,Object> invitation:invitations.getOrDefault(d.candidate().teacherId(),List.of())) {
                    var invited=DispatchConfirmationGuard.teaching(invitation);
                    if(invited==null?DispatchConfirmationGuard.potentiallyRelevant(invitation,proposed):DispatchConfirmationGuard.overlap(proposed,invited)) {invitationNotices.add(d.candidate().teacherId());break;}
                }
            }
            ranking.add(new RecommendationRanking.Input(d,RecommendationRanking.IdentityStatus.CONFIRMED,travel));}
        boolean canRecord=false;if(context!=null)try{RecommendationAssessmentIntegration.context(s,in.base.scope(),in.contextType,in.contextId,true);canRecord=true;}catch(Api.ApiException denied){if(denied.code!=403)throw denied;}
        return new V2Capture(in,snapshot,rankContext,List.copyOf(ranking),List.copyOf(decisions),privateVersion,Map.copyOf(assessments),canRecord,Set.copyOf(invitationNotices));
    }
    private static void sweepV2(long now){V2_CACHE.entrySet().removeIf(e->now-e.getValue().created>=TTL);}
    private static List<String> mergeMessagesV2(Object existing,List<String> codes){List<String> messages=new ArrayList<>();if(existing instanceof List<?> list)for(Object item:list)messages.add(String.valueOf(item));Map<String,String> labels=Map.ofEntries(Map.entry("TRAVEL_FACTS_UNKNOWN","本次出行方式、可达与缓冲待核"),Map.entry("DISTANCE_UNKNOWN","实际出发地到授课地点的距离未知，不按零比较"),Map.entry("COST_UNKNOWN","整次交通费用未知或口径不可比，不声称最低成本"),Map.entry("IDENTITY_UNCONFIRMED","稳定教师身份待核"),Map.entry("DOOR_TO_DOOR_UNKNOWN","门到门时长待核"),Map.entry("BEFORE_BUFFER_UNKNOWN","课前出行缓冲待核"),Map.entry("AFTER_BUFFER_UNKNOWN","课后出行缓冲待核"),Map.entry("TRAVEL_FEASIBILITY_UNKNOWN","本次交通可达待核"));for(String code:codes)if(labels.containsKey(code))messages.add(labels.get(code));return new ArrayList<>(new LinkedHashSet<>(messages));}
    @SuppressWarnings("unchecked") private static Map<String,Object> objectV2(Object raw)throws Api.ApiException {if(!(raw instanceof Map<?,?>))throw failure(400,"参数须为对象");return (Map<String,Object>)raw;}

}
