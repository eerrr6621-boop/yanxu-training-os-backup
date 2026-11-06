package com.training;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static com.training.RecommendationDecision.*;

/** Final explicit confirmation only. Shortlists never reserve a teacher. */
final class DispatchConfirmationGuard {
    private DispatchConfirmationGuard() {}
    private static final byte[] SECRET=new byte[32];
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    static {new SecureRandom().nextBytes(SECRET);}
    record Occupied(Instant start,Instant end,boolean bufferKnown,String internalVersion) {}
    record Check(Map<String,Object> row,String version,Occupied window,List<String> blocks,List<String> pending,Object privateBasis) {}
    static void init()throws SQLException {
        Db.exec("CREATE TABLE IF NOT EXISTS dispatch_confirmation_occupancy(dispatch_id BIGINT PRIMARY KEY REFERENCES dispatches(id),teacher_id BIGINT NOT NULL,payload CLOB NOT NULL,digest VARCHAR(64) NOT NULL)");
        Db.exec("CREATE INDEX IF NOT EXISTS idx_dispatch_confirm_teacher ON dispatch_confirmation_occupancy(teacher_id)");
        Db.exec("CREATE TABLE IF NOT EXISTS dispatch_confirmation_requests(account_id BIGINT NOT NULL,request_id VARCHAR(96) NOT NULL,dispatch_id BIGINT NOT NULL,payload CLOB NOT NULL,result CLOB NOT NULL,digest VARCHAR(64) NOT NULL,PRIMARY KEY(account_id,request_id))");
    }
    static String opaque(String value){try {Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(SECRET,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    static Map<String,Object> preview(Auth.Session session,long id)throws Exception {
        synchronized(Api.MUTATION_LOCK){integer(id,false);Check c=check(session,id);return map("dispatch_id",id,"expected_version",c.version(),"can_confirm",c.blocks().isEmpty(),"blocks",c.blocks(),"pending",c.pending(),"buffer_verified",c.window().bufferKnown(),"notice","这是有权操作人登记的最终确认；不会把手工课程标为资格认证，备选名单不占用老师。");}
    }
    static Map<String,Object> confirm(Auth.Session session,Map<String,Object> body)throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            long id=integer(body.get("id"),false);authorize(session,id);
            if(!body.containsKey("expected_version")||!body.containsKey("request_id"))throw error(409,"VERSION_REQUIRED：请刷新并核对当前排课后再确认");
            if(!body.keySet().equals(Set.of("id","accept","expected_version","request_id"))||integer(body.get("accept"),false)!=1)throw error(400,"确认字段无效");
            String version=text(body.get("expected_version"),64),request=text(body.get("request_id"),96);
            if(!version.matches("[a-f0-9]{64}")||!request.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,95}"))throw error(400,"确认版本或请求编号无效");
            String payload=canonical(map("id",id,"accept",1,"expected_version",version,"request_id",request));
            if(!Db.get().getAutoCommit())throw error(409,"确认不能嵌套其他业务事务");
            return Db.transaction(()->{
                Map<String,Object> row=authorize(session,id);
                // Serialize this stable global teacher identity within the same transaction.
                if(Db.one("SELECT id FROM teachers WHERE id=? FOR UPDATE",row.get("teacher_id"))==null)throw error(409,"教师档案已变化");
                Map<String,Object> old=Db.one("SELECT * FROM dispatch_confirmation_requests WHERE account_id=? AND request_id=?",session.uid,request);
                if(old!=null){verifyReceipt(old);if(integer(old.get("dispatch_id"),false)!=id||!payload.equals(old.get("payload")))throw error(409,"同一请求编号不能用于不同确认内容");validateFrozen(row);return Json.parseMap((String)old.get("result"));}
                Check c=check(session,id);
                if(!version.equals(c.version()))throw error(409,"确认依据已变化，请刷新重新核对");
                if(!c.blocks().isEmpty())throw error(409,String.join("；",c.blocks()));
                String now=Instant.now().toString(),resultVersion=UUID.randomUUID().toString();
                Map<String,Object> frozen=map("schema","DISPATCH-CONFIRMATION-1","dispatch_id",id,"teacher_id",row.get("teacher_id"),"core",core(row),"start",c.window().start().toString(),"end",c.window().end().toString(),"buffer_known",c.window().bufferKnown(),"basis",c.privateBasis(),"actor_account",session.uid,"created_at",now,"result_version",resultVersion);
                String saved=canonical(frozen);
                if(Db.one("SELECT dispatch_id FROM dispatch_confirmation_occupancy WHERE dispatch_id=?",id)!=null)throw error(409,"排课已有确认冻结记录，请核对原记录");
                Db.exec("INSERT INTO dispatch_confirmation_occupancy VALUES(?,?,?,?)",id,row.get("teacher_id"),saved,hash(saved));
                String previous=Objects.toString(row.get("msg_log"),""),name=Objects.toString(row.get("teacher_name"),"师资");
                Db.exec("UPDATE dispatches SET status='已确认',confirmed_at=?,msg_log=? WHERE id=?",now,previous+(previous.isEmpty()?"":"\n")+"【"+now+"】"+name+"已确认授课安排。",id);
                Map<String,Object> result=map("status","CONFIRMED","dispatch_id",id,"result_version",resultVersion,"message","师资已确认","qualification_certified",false,"pending",c.pending());
                String response=canonical(result);
                if(integer(Db.one("SELECT COUNT(*) n FROM dispatch_confirmation_requests").get("n"),true)>=20000)throw error(409,"确认审计已达到容量上限");
                Db.exec("INSERT INTO dispatch_confirmation_requests VALUES(?,?,?,?,?,?)",session.uid,request,id,payload,response,receiptHash(session.uid,request,id,payload,response));
                authorize(session,id);validateFrozen(authorize(session,id));return result;
            });
        }
    }
    private static Check check(Auth.Session session,long id)throws Exception {
        Map<String,Object> row=authorize(session,id);long teacher=integer(row.get("teacher_id"),false);
        List<String> blocks=new ArrayList<>(),pending=new ArrayList<>();
        if(!"已发送".equals(row.get("status")))blocks.add("请先记录已通知师资，再登记确认结果");
        if(!"在库".equals(row.get("teacher_status")))blocks.add("该师资当前不在库");
        if(!Set.of("待启动","进行中").contains(row.get("project_status")))blocks.add("项目已完成或归档，不能确认新的授课安排");
        Occupied own=teaching(row);if(own==null)throw error(409,"请先按原编辑流程补全授课日期及完整时段，再发送并核对确认");
        // Explicit historical registration is independent of new workflow acceptances.
        Map<String,Object> historical=LegacyOwnershipIntegration.source("PROJECT",integer(row.get("project_id"),false));
        if(Db.one("SELECT project_id FROM workflow_acceptances WHERE project_id=?",row.get("project_id"))==null&&historical==null)throw error(409,"该历史项目尚未登记机构归属，请由全局系统管理员在历史归属登记入口核实后办理");
        List<RecommendationAssessmentIntegration.Located> assessments=RecommendationAssessmentIntegration.forDispatch(session,row);
        List<Object> factVersions=new ArrayList<>();Map<String,Map<String,Object>> courses=new LinkedHashMap<>();
        Map<String,Object> linked=CourseActivityIntegration.confirmationCourse(session,row);
        if(linked!=null)courses.put(linked.get("scope_id")+":"+linked.get("course_code"),linked);
        int before=0,after=0;boolean known=false,currentFacts=false;Set<String> modes=new HashSet<>();
        for(var located:assessments){var snap=located.snapshot();var f=snap.facts();factVersions.add(map("assessment",snap.assessmentId(),"revision",snap.revision(),"hash",snap.internalHash(),"current",f!=null&&f.current(Instant.now())));
            String course=Objects.toString(located.key().request().get("course_code"),"");
            if(!course.isEmpty())courses.put(located.key().context().scopeId()+":"+course,map("scope_id",located.key().context().scopeId(),"course_code",course));
            if(f==null)continue;
            if(!Objects.equals(f.teachingPlace(),Objects.toString(row.get("venue"),""))){pending.add("已有核对记录的目的地与本次排课不同，不能作为可达依据");continue;}
            boolean online="ONLINE".equals(located.key().request().get("mode"));
            // Online teaching needs no travel buffer; retain known onsite buffers even after quote expiry.
            if(!online){if(f.beforeBuffer()!=null)before=Math.max(before,f.beforeBuffer());if(f.afterBuffer()!=null)after=Math.max(after,f.afterBuffer());}
            if(!f.current(Instant.now()))continue;currentFacts=true;modes.add(Objects.toString(located.key().request().get("mode"),"UNKNOWN"));
            if(f.service()!=Confirmation.CONFIRMED||f.availability()!=Confirmation.CONFIRMED)pending.add("本次服务或完整档期仍有待核事项");
            if(f.service()==Confirmation.DECLINED||f.availability()==Confirmation.DECLINED||f.feasibility()==Confirmation.DECLINED)blocks.add("本次核对已有明确不可承接或不可用事实，请先核实原记录");
            known|=online||f.feasibility()==Confirmation.CONFIRMED&&f.beforeBuffer()!=null&&f.afterBuffer()!=null&&f.doorMinutes()!=null&&!f.departurePlace().isBlank()&&!f.teachingPlace().isBlank();
        }
        if(modes.size()>1){known=false;pending.add("相关核对记录的授课形式不一致，出行安排仍待核");}
        if(!currentFacts)pending.add("本次服务和完整档期核对资料待确认；最终登记不等于推荐资格完成");
        if(!known)pending.add("出行缓冲待核；当前仅核对已知授课时段及已记录缓冲，未证明交通可达");
        List<Object> qualification=new ArrayList<>();
        if(courses.isEmpty())pending.add("手工课程资格待核；本次确认不表示已经取得标准课程认证");
        for(var course:courses.values()) {
            long scope=integer(course.get("scope_id"),false);String code=text(course.get("course_code"),64);
            Map<String,Object> current=CourseCatalogIntegration.readCourse(session,scope,code);long rev=integer(current.get("version"),true);
            Map<String,Object> q=CourseCatalogIntegration.qualification(session,scope,rev,map("course_code",code,"as_of",row.get("teach_date"),"accepted_levels",List.of(),"allowed_cities",List.of()));
            qualification.add(map("course",current,"context",q.get("qualification_context")));boolean found=false,eligible=false;List<String> codes=new ArrayList<>();
            for(String group:List.of("eligible","gaps"))for(Map<String,Object> item:rows(q.get(group)))if(integer(item.get("teacher_id"),false)==teacher){found=true;eligible=Boolean.TRUE.equals(item.get("eligible"));for(Map<String,Object> reason:rows(item.get("reasons")))codes.add(Objects.toString(reason.get("code"),""));}
            if(!Boolean.TRUE.equals(current.get("active"))||codes.stream().anyMatch(Set.of("NOT_CERTIFIED","CERTIFICATION_REVOKED","NOT_YET_VALID","EXPIRED","LEVEL_NOT_ALLOWED","CITY_NOT_ALLOWED","TEACHER_NOT_ACTIVE","COURSE_INACTIVE")::contains))blocks.add("本次明确标准课程的当前资格不符合，请核对原课程认证");
            else if(!found||!eligible||!"READY".equals(q.get("status")))pending.add("已关联标准课程资格尚不能完整核对，不标为资格认证完成");
        }
        Occupied window=new Occupied(own.start().minusSeconds(before*60L),own.end().plusSeconds(after*60L),known,"");
        List<Object> occupation=new ArrayList<>();boolean unknown=false,conflict=false;
        for(Map<String,Object> other:bookings(teacher)) {
            if(integer(other.get("id"),false)==id)continue;Occupied busy=occupied(other);occupation.add(hash(canonical(map("row",core(other),"status",other.get("status"),"frozen",busy==null?null:busy.internalVersion()))));
            if(busy==null){if(potentiallyRelevant(other,window))unknown=true;continue;}if(overlap(window,busy))conflict=true;
        }
        if(conflict)blocks.add("该教师在本次时段或已知出行缓冲内不可用，请重新选择或核对");
        if(unknown)blocks.add("该教师有无法完整核对的已确认安排，请先核对档期");
        Map<String,Object> basis=map("core",core(row),"status",row.get("status"),"teacher_status",row.get("teacher_status"),"project_status",row.get("project_status"),"facts",factVersions,"qualification",qualification,"occupation",occupation,"identity",OrganizationAccessStore.configuration().version(),"historical_source",historical,"buffer_before",before,"buffer_after",after,"buffer_known",known);
        String version=opaque(canonical(map("account",session.uid,"basis",basis)));
        return new Check(row,version,window,List.copyOf(new LinkedHashSet<>(blocks)),List.copyOf(new LinkedHashSet<>(pending)),basis);
    }
    static List<Map<String,Object>> bookings(long teacher)throws Exception {List<Map<String,Object>> rows=Db.query("SELECT * FROM dispatches WHERE teacher_id=? AND status IN ('已确认','已完成') ORDER BY id LIMIT 5001",teacher);if(rows.size()>5000)throw error(409,"教师档期超出本次安全核对容量");return rows;}
    static Occupied occupied(Map<String,Object> row)throws Exception {Map<String,Object> frozen=validateFrozen(row);if(frozen==null)return teaching(row);return new Occupied(Instant.parse((String)frozen.get("start")),Instant.parse((String)frozen.get("end")),Boolean.TRUE.equals(frozen.get("buffer_known")),hash(canonical(frozen)));}
    static boolean potentiallyRelevant(Map<String,Object> row,Occupied proposed) {
        try {LocalDate day=LocalDate.parse(Objects.toString(row.get("teach_date"),""));Occupied uncertain=new Occupied(day.atStartOfDay(ZONE).toInstant(),day.plusDays(1).atStartOfDay(ZONE).toInstant(),false,"");return overlap(uncertain,proposed);}catch(DateTimeException invalid){return true;}
    }
    static boolean overlap(Occupied a,Occupied b){return a.start().isBefore(b.end())&&b.start().isBefore(a.end());}
    static Occupied teaching(Map<String,Object> row) {
        try {String day=Objects.toString(row.get("teach_date"),""),start=Objects.toString(row.get("start_time"),""),end=Objects.toString(row.get("end_time"),"");if(!day.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")||!start.matches("[0-2][0-9]:[0-5][0-9]")||!end.matches("[0-2][0-9]:[0-5][0-9]")||start.compareTo(end)>=0)return null;return new Occupied(LocalDateTime.parse(day+"T"+start).atZone(ZONE).toInstant(),LocalDateTime.parse(day+"T"+end).atZone(ZONE).toInstant(),false,hash(canonical(core(row))));}catch(DateTimeException invalid){return null;}
    }
    private static Map<String,Object> validateFrozen(Map<String,Object> row)throws Exception {
        Map<String,Object> db=Db.one("SELECT * FROM dispatch_confirmation_occupancy WHERE dispatch_id=?",row.get("id"));if(db==null)return null;
        try {String payload=(String)db.get("payload");if(payload==null||payload.length()>1048576||!hash(payload).equals(db.get("digest")))throw error(409,"确认冻结记录无法核实");Map<String,Object> value=Json.parseMap(payload);
            if(!value.keySet().equals(Set.of("schema","dispatch_id","teacher_id","core","start","end","buffer_known","basis","actor_account","created_at","result_version"))||!"DISPATCH-CONFIRMATION-1".equals(value.get("schema"))||!canonical(value.get("core")).equals(canonical(core(row)))||integer(value.get("dispatch_id"),false)!=integer(row.get("id"),false)||integer(value.get("teacher_id"),false)!=integer(row.get("teacher_id"),false)||integer(db.get("teacher_id"),false)!=integer(row.get("teacher_id"),false)||!(value.get("buffer_known") instanceof Boolean))throw error(409,"确认冻结记录与排课来源不一致");
            Instant start=Instant.parse(text(value.get("start"),40)),end=Instant.parse(text(value.get("end"),40));Occupied teaching=teaching(row);if(teaching==null||!start.isBefore(end)||start.isAfter(teaching.start())||end.isBefore(teaching.end()))throw error(409,"确认冻结窗口无效");integer(value.get("actor_account"),false);Instant.parse(text(value.get("created_at"),40));text(value.get("result_version"),36);return value;
        }catch(IllegalArgumentException|DateTimeException invalid){throw error(409,"确认冻结记录无法核实");}
    }
    private static Map<String,Object> authorize(Auth.Session s,long id)throws Exception {if(s==null||Auth.current(s)!=s)throw error(401,"登录已失效");if(!Auth.canWrite(s))throw error(403,"当前账号不能确认排课");Map<String,Object> row=Db.one("SELECT d.*,t.name teacher_name,t.status teacher_status,p.status project_status FROM dispatches d JOIN teachers t ON t.id=d.teacher_id JOIN projects p ON p.id=d.project_id WHERE d.id=?",id);if(row==null)throw error(404,"排课不存在");WorkflowIntegration.requireProjectAccess(integer(row.get("project_id"),false),s,true);return row;}
    private static Map<String,Object> core(Map<String,Object> row){Map<String,Object> result=new LinkedHashMap<>();for(String field:List.of("id","project_id","teacher_id","subject","teach_date","start_time","end_time","venue","confirm_deadline","hours"))result.put(field,row.get(field));return result;}
    private static void verifyReceipt(Map<String,Object> r)throws Exception {long account=integer(r.get("account_id"),false),id=integer(r.get("dispatch_id"),false);String request=text(r.get("request_id"),96),payload=text(r.get("payload"),4096),result=text(r.get("result"),16384);if(!receiptHash(account,request,id,payload,result).equals(r.get("digest")))throw error(409,"确认回执无法核实");Map<String,Object> v=Json.parseMap(result);Map<String,Object> frozen=Db.one("SELECT payload FROM dispatch_confirmation_occupancy WHERE dispatch_id=?",id);if(frozen==null||!"CONFIRMED".equals(v.get("status"))||integer(v.get("dispatch_id"),false)!=id||!Objects.equals(v.get("result_version"),Json.parseMap((String)frozen.get("payload")).get("result_version")))throw error(409,"确认回执结果来源不一致");}
    private static String receiptHash(long account,String request,long id,String payload,String result){return hash(canonical(map("account",account,"request",request,"dispatch",id,"payload",payload,"result",result)));}
    private static String canonical(Object value){return TrainingSummariesWorkflow.canonical(value);}
    private static long integer(Object raw,boolean zero)throws Api.ApiException {try {if(!(raw instanceof Number))throw new IllegalArgumentException();long value=new java.math.BigDecimal(raw.toString()).longValueExact();if(value<(zero?0:1)||value>9007199254740991L)throw new IllegalArgumentException();return value;}catch(RuntimeException invalid){throw error(400,"编号须为安全整数");}}
    private static String text(Object raw,int max)throws Api.ApiException {if(!(raw instanceof String s)||s.isBlank()||s.length()>max)throw error(400,"参数文字无效");return s;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object raw){return raw instanceof List<?> list?(List<Map<String,Object>>)list:List.of();}
    private static Api.ApiException error(int code,String message){return new Api.ApiException(code,message);}
}
