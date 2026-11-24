package com.training;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import static com.training.RecommendationDecision.*;

/** Staff-recorded, request-specific facts. Never grants course qualification or creates a booking. */
final class RecommendationAssessmentIntegration {
    private RecommendationAssessmentIntegration() {}
    private static final int MAX_REVISIONS=200, MAX_REQUESTS=20000;
    private static final String ZERO="0".repeat(64);
    private static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    record Context(long scopeId,String type,long id,String organization,String sourceVersion) {
        Context(long scopeId,String type,long id,String organization){this(scopeId,type,id,organization,"");}
    }
    record Key(Context context,long teacherId,String fingerprint,Map<String,Object> request) {}
    record Facts(Confirmation service,Confirmation availability,Instant availableFrom,Instant availableUntil,
                 Confirmation feasibility,String mode,String departurePlace,String teachingPlace,
                 Integer legMinutes,Integer doorMinutes,Integer beforeBuffer,Integer afterBuffer,
                 BigDecimal travelCost,String currency,String costCoverage,Long distanceMeters,String distanceCoverage,Instant observedAt,Instant validUntil,String note) {
        boolean current(Instant now){return !now.isBefore(observedAt)&&now.isBefore(validUntil);}
        Map<String,Object> value(){return map("service",service.name(),"availability",availability.name(),
            "available_from",iso(availableFrom),"available_until",iso(availableUntil),"travel_feasibility",feasibility.name(),
            "transport_mode",mode,"departure_place",departurePlace,"teaching_place",teachingPlace,"leg_minutes",legMinutes,
            "door_to_door_minutes",doorMinutes,"before_buffer_minutes",beforeBuffer,"after_buffer_minutes",afterBuffer,
            "travel_cost",travelCost==null?null:travelCost.toPlainString(),"currency",currency,"cost_coverage",costCoverage,
            "distance_km",distanceMeters==null?null:BigDecimal.valueOf(distanceMeters,3).stripTrailingZeros().toPlainString(),"distance_coverage",distanceCoverage,"source_observed_at",observedAt.toString(),"valid_until",validUntil.toString(),"source_note",note,"source_kind","STAFF_RECORDED");}
    }
    record Snapshot(String assessmentId,long revision,Facts facts,String internalHash,Map<String,Object> audit,Map<String,Object> review) {
        Map<String,Object> view(){return map("assessment_id",assessmentId,"revision",revision,"facts",facts==null?null:facts.value(),
            "recorded_by",audit.get("actor_name"),"recorded_at",audit.get("created_at"),"reason",audit.get("reason"),"experience_review",review,
            "statement","有权操作人记录的本次核对事实，不是教师本人签认，不创建课程资格、通知或派课");}
    }
    static void init()throws SQLException {
        Db.exec("CREATE TABLE IF NOT EXISTS recommendation_assessment_heads(basis_key VARCHAR(64) PRIMARY KEY,assessment_id VARCHAR(36) UNIQUE NOT NULL,scope_id BIGINT NOT NULL,context_type VARCHAR(8) NOT NULL,context_id BIGINT NOT NULL,organization_code VARCHAR(120) NOT NULL,teacher_id BIGINT NOT NULL,request_hash VARCHAR(64) NOT NULL,revision BIGINT NOT NULL,head_hash VARCHAR(64) NOT NULL,teach_date VARCHAR(10) NOT NULL,start_time VARCHAR(5) NOT NULL,end_time VARCHAR(5) NOT NULL,topic VARCHAR(500) NOT NULL,teaching_place VARCHAR(200) NOT NULL)");
        Db.exec("CREATE TABLE IF NOT EXISTS recommendation_assessment_events(assessment_id VARCHAR(36) NOT NULL,revision BIGINT NOT NULL,payload CLOB NOT NULL,previous_hash VARCHAR(64) NOT NULL,event_hash VARCHAR(64) NOT NULL,PRIMARY KEY(assessment_id,revision))");
        Db.exec("CREATE TABLE IF NOT EXISTS recommendation_assessment_requests(account_id BIGINT NOT NULL,request_id VARCHAR(96) NOT NULL,basis_key VARCHAR(64) NOT NULL,payload_hash VARCHAR(64) NOT NULL,assessment_id VARCHAR(36) NOT NULL,result_revision BIGINT NOT NULL,receipt_hash VARCHAR(64) NOT NULL,PRIMARY KEY(account_id,request_id))");
        Db.exec("CREATE INDEX IF NOT EXISTS idx_recommendation_assessment_context ON recommendation_assessment_heads(context_type,context_id,teacher_id,teach_date,start_time,end_time)");
    }
    static Context context(Auth.Session session,long scopeId,String type,long id,boolean write)throws Exception {
        live(session,write);
        if(scopeId<1||id<1||!Set.of("DEMAND","PROJECT").contains(type))throw fail(400,"本次核对须关联明确的需求或项目");
        Map<String,Object> scope=Db.one("SELECT organization_code FROM m04_catalog_scopes WHERE scope_id=?",scopeId);
        if(scope==null)throw fail(404,"课程范围不存在");String org=(String)scope.get("organization_code");
        require(session,"catalog.read",OrganizationAccess.Action.VIEW,org);
        Map<String,Object> legacy=LegacyOwnershipIntegration.source(type,id);
        if(legacy!=null) {
            if(!org.equals(legacy.get("organization_code")))throw fail(403,"业务与课程机构范围不一致");
            require(session,"demand.read",OrganizationAccess.Action.VIEW,org);
            if(write)require(session,"demand.write",OrganizationAccess.Action.HANDLE,org);
            if(type.equals("PROJECT")){WorkflowIntegration.requireProjectAccess(id,session,false);if(write)WorkflowIntegration.requireProjectAccess(id,session,true);}
            else {
                Map<String,Object> row=Db.one("SELECT * FROM demands WHERE id=?",id);
                if(row==null||WorkflowReadAccessBridge.listRows("demands",List.of(row),session,Map.of()).isEmpty())throw fail(403,"没有该需求的读取权限");
            }
            return new Context(scopeId,type,id,org,text(legacy.get("source_version"),128,false));
        }
        if(type.equals("PROJECT")) {
            WorkflowIntegration.requireProjectAccess(id,session,false);
            if(write)WorkflowIntegration.requireProjectAccess(id,session,true);
            SurveySummaryImportsIntegration.Source source=SurveySummaryImportsIntegration.trustedSource(id);
            if(!org.equals(source.org()))throw fail(403,"业务与课程机构范围不一致");
        } else {
            Map<String,Object> row=Db.one("SELECT d.*,w.organization_code FROM demands d JOIN workflow_demands w ON w.demand_id=d.id WHERE d.id=?",id);
            if(row==null)throw fail(404,"无法核对该需求的机构来源");
            require(session,"demand.read",OrganizationAccess.Action.VIEW,(String)row.get("organization_code"));
            if(write)require(session,"demand.write",OrganizationAccess.Action.HANDLE,(String)row.get("organization_code"));
            if(!org.equals(row.get("organization_code")))throw fail(403,"业务与课程机构范围不一致");
            if(WorkflowReadAccessBridge.listRows("demands",List.of(row),session,Map.of()).isEmpty())throw fail(403,"没有该需求的读取权限");
        }
        return new Context(scopeId,type,id,org);
    }
    static Key key(Auth.Session session,Context context,long teacherId,Map<String,Object> raw,boolean write)throws Exception {
        Context current=context(session,context.scopeId(),context.type(),context.id(),write);
        if(!current.equals(context))throw fail(409,"业务机构来源已变化");
        Map<String,Object> request=request(raw);
        CourseCatalogIntegration.readCourses(session,current.scopeId());
        Map<String,Object> head=Db.one("SELECT version FROM m04_catalog_scopes WHERE scope_id=?",current.scopeId());
        Map<String,Object> rev=Db.one("SELECT bindings FROM m04_catalog_revisions WHERE scope_id=? AND version=?",current.scopeId(),head.get("version"));
        boolean bound=false;
        if(rev!=null)for(Object item:list(Json.parse((String)rev.get("bindings"))))if(item instanceof Map<?,?> b&&number(b.get("teacher_id"),false)==teacherId)bound=true;
        Map<String,Object> teacher=Db.one("SELECT id,name,status FROM teachers WHERE id=?",teacherId);
        if(!bound||teacher==null||WorkflowReadAccessBridge.listRows("teachers",List.of(teacher),session,Map.of()).isEmpty())throw fail(403,"本范围没有可核对的该教师档案");
        String fp=basis(current,teacherId,request);
        return new Key(current,teacherId,fp,Collections.unmodifiableMap(request));
    }
    /** Called only after host validates this context, catalog and visible teacher binding in the same lock. */
    static Snapshot captured(Context context,long teacherId,Map<String,Object> raw)throws Exception {
        Map<String,Object> request=request(raw);
        String fp=basis(context,teacherId,request);
        return stored(new Key(context,teacherId,fp,request),0);
    }
    private static String basis(Context context,long teacherId,Map<String,Object> request) {
        Map<String,Object> value=map("scope_id",context.scopeId(),"context_type",context.type(),"context_id",context.id(),"organization_code",context.organization(),"teacher_id",teacherId,"request",request);
        // Existing workflow assessment bytes/keys remain stable; only registered legacy provenance adds a source binding.
        if(!context.sourceVersion().isEmpty())value.put("legacy_source_version",context.sourceVersion());
        return hash(canonical(value));
    }
    static Snapshot read(Auth.Session session,Key input)throws Exception {
        Key checked=key(session,input.context(),input.teacherId(),input.request(),false);
        if(!checked.fingerprint().equals(input.fingerprint()))throw fail(409,"本次核对条件已变化");
        return stored(checked,0);
    }
    record Located(Key key,Snapshot snapshot) {}
    static Located locate(Auth.Session session,String assessmentId)throws Exception {
        if(assessmentId==null||!assessmentId.matches("[a-f0-9-]{36}"))throw fail(400,"核对记录编号无效");
        live(session,false);
        Map<String,Object> head=Db.one("SELECT * FROM recommendation_assessment_heads WHERE assessment_id=?",assessmentId);
        if(head==null)throw fail(404,"核对记录不存在");
        Context context=context(session,number(head.get("scope_id"),false),(String)head.get("context_type"),number(head.get("context_id"),false),false);
        Map<String,Object> last=Db.one("SELECT payload FROM recommendation_assessment_events WHERE assessment_id=? AND revision=?",assessmentId,head.get("revision"));
        if(last==null)throw corrupt();
        Map<String,Object> raw=Json.parseMap((String)last.get("payload"));
        Key key=key(session,context,number(head.get("teacher_id"),false),object(raw.get("request")),false);
        if(!key.fingerprint().equals(head.get("basis_key")))throw fail(409,"核对记录的历史业务来源已变化，请重新核对");
        return new Located(key,stored(key,0));
    }
    /** Only considers the caller's own authorized dispatch context, never another institution's assessments. */
    static List<Located> forDispatch(Auth.Session session,Map<String,Object> dispatch)throws Exception {
        long project=number(dispatch.get("project_id"),false),teacher=number(dispatch.get("teacher_id"),false);
        WorkflowIntegration.requireProjectAccess(project,session,false);
        Map<String,Object> accepted=Db.one("SELECT demand_id FROM workflow_acceptances WHERE project_id=?",project);
        Map<String,Object> legacy=accepted==null?LegacyOwnershipIntegration.source("PROJECT",project):null;
        if(accepted==null&&legacy==null)return List.of(); // Unregistered legacy projects still have no trusted source.
        long demand=number((accepted==null?legacy:accepted).get("demand_id"),true);
        List<Map<String,Object>> rows=Db.query("SELECT assessment_id FROM recommendation_assessment_heads WHERE teacher_id=? AND ((context_type='PROJECT' AND context_id=?) OR (context_type='DEMAND' AND context_id=?)) AND teach_date=? AND start_time=? AND end_time=? AND topic=? AND teaching_place=? ORDER BY assessment_id LIMIT 101",teacher,project,demand,Objects.toString(dispatch.get("teach_date"),""),Objects.toString(dispatch.get("start_time"),""),Objects.toString(dispatch.get("end_time"),""),Objects.toString(dispatch.get("subject"),""),Objects.toString(dispatch.get("venue"),""));
        if(rows.size()>100)throw fail(409,"本次核对记录过多，请明确选择并检查原记录");
        List<Located> result=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            Located located=locate(session,(String)row.get("assessment_id"));Map<String,Object> request=located.key().request();
            if(Objects.equals(request.get("date"),dispatch.get("teach_date"))&&Objects.equals(request.get("start_time"),Objects.toString(dispatch.get("start_time"),""))&&Objects.equals(request.get("end_time"),Objects.toString(dispatch.get("end_time"),""))&&Objects.equals(request.get("topic"),dispatch.get("subject")))result.add(located);
        }
        return List.copyOf(result);
    }
    interface ReviewValidation {void check(Map<String,Object> review)throws Exception;}
    static Map<String,Object> save(Auth.Session session,Key input,long expected,long expectedCatalogVersion,String requestId,Map<String,Object> rawFacts,String reason)throws Exception {
        return save(session,input,expected,expectedCatalogVersion,requestId,rawFacts,reason,false,null,review->{});
    }
    static Map<String,Object> save(Auth.Session session,Key input,long expected,long expectedCatalogVersion,String requestId,Map<String,Object> rawFacts,String reason,boolean reviewPresent,Map<String,Object> rawReview,ReviewValidation validateReview)throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            Key checked=key(session,input.context(),input.teacherId(),input.request(),true);
            if(!checked.fingerprint().equals(input.fingerprint()))throw fail(409,"本次核对条件已变化");
            if(expected<0||expected>9007199254740991L)throw fail(400,"核对版本无效");
            requestId=text(requestId,96,false);if(!requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,95}"))throw fail(400,"请提供有效的请求编号");
            Facts facts=facts(rawFacts);reason=text(reason,1000,false);
            final Map<String,Object> review;
            try {review=reviewPresent&&rawReview!=null?RecommendationExperienceReview.normalize(rawReview):null;}
            catch(IllegalArgumentException invalid){throw fail(400,"课程经历确认结构无效，请按固定字段逐项核对");}
            Map<String,Object> requestPayload=map("basis",checked.fingerprint(),"expected_revision",expected,"expected_catalog_version",expectedCatalogVersion,"facts",facts.value(),"reason",reason);
            // Preserve the original v1 idempotency digest exactly; even an extra null field would change it.
            if(reviewPresent)requestPayload.put("experience_review",review);
            String request=requestId,why=reason,payloadHash=hash(canonical(requestPayload));
            if(!Db.get().getAutoCommit())throw fail(409,"核对保存不能嵌套其他业务事务");
            // Source capture intentionally requires auto-commit. This complete preflight and the following
            // transaction share MUTATION_LOCK, so application writes cannot alter sources between them.
            // Existing receipts must reach historical replay even after material/catalog changes.
            if(review!=null&&Db.one("SELECT request_id FROM recommendation_assessment_requests WHERE account_id=? AND request_id=?",session.uid,request)==null)validateReview.check(review);
            return Db.transaction(()-> {
                key(session,checked.context(),checked.teacherId(),checked.request(),true);
                Snapshot before=stored(checked,0);
                Map<String,Object> old=Db.one("SELECT * FROM recommendation_assessment_requests WHERE account_id=? AND request_id=?",session.uid,request);
                if(old!=null) {
                    verifyReceipt(old);
                    if(!checked.fingerprint().equals(old.get("basis_key"))||!payloadHash.equals(old.get("payload_hash")))throw fail(409,"同一请求编号对应的核对内容不同");
                    Snapshot result=stored(checked,number(old.get("result_revision"),false));
                    if(!result.assessmentId().equals(old.get("assessment_id")))throw corrupt();
                    return map("result",result.view(),"current_revision",before.revision(),"replayed",true);
                }
                if(number(Db.one("SELECT version FROM m04_catalog_scopes WHERE scope_id=?",checked.context().scopeId()).get("version"),true)!=expectedCatalogVersion)throw fail(409,"课程目录版本已变化，请刷新后核对");
                if(!"在库".equals(Db.one("SELECT status FROM teachers WHERE id=?",checked.teacherId()).get("status")))throw fail(409,"该教师当前不在库，不能建立新的核对依据");
                validateForRequest(facts,checked.request(),Instant.now());
                if(before.revision()!=expected)throw fail(409,"核对依据已更新，请重新读取");
                if(expected>=MAX_REVISIONS)throw fail(409,"本次核对审计版本达到上限");
                if(!reviewPresent&&before.review()!=null)throw fail(409,"已有经历确认，请显式保留或清除后保存，不能由旧表单隐式删除");
                String id=before.assessmentId().isEmpty()?UUID.randomUUID().toString():before.assessmentId();long next=expected+1;
                String name=session.name==null||session.name.isBlank()?session.username:session.name;
                Map<String,Object> row=map("format",reviewPresent?"RECOMMENDATION-ASSESSMENT-2":"RECOMMENDATION-ASSESSMENT-1","assessment_id",id,"revision",next,"basis_key",checked.fingerprint(),
                    "scope_id",checked.context().scopeId(),"organization_code",checked.context().organization(),"context_type",checked.context().type(),"context_id",checked.context().id(),
                    "teacher_id",checked.teacherId(),"request",checked.request(),"facts",facts.value(),"actor_account",session.uid,
                    "actor_person",OrganizationAccessStore.person(session).personCode(),"actor_name",name,"created_at",Instant.now().toString(),"reason",why);
                if(reviewPresent)row.put("experience_review",review);
                if(!checked.context().sourceVersion().isEmpty()) {
                    row.put("format","RECOMMENDATION-ASSESSMENT-3");row.put("legacy_source_version",checked.context().sourceVersion());row.put("experience_review",review);
                }
                String payload=canonical(row),digest=hash(before.internalHash()+"\n"+payload);
                if(payload.length()>32768)throw fail(413,"本次核对记录超出审计容量");
                Db.exec("INSERT INTO recommendation_assessment_events VALUES(?,?,?,?,?)",id,next,payload,before.internalHash(),digest);
                if(expected==0)Db.exec("INSERT INTO recommendation_assessment_heads VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",checked.fingerprint(),id,checked.context().scopeId(),checked.context().type(),checked.context().id(),checked.context().organization(),checked.teacherId(),hash(canonical(checked.request())),next,digest,checked.request().get("date"),checked.request().get("start_time"),checked.request().get("end_time"),checked.request().get("topic"),checked.request().get("teaching_place"));
                else Db.exec("UPDATE recommendation_assessment_heads SET revision=?,head_hash=? WHERE basis_key=?",next,digest,checked.fingerprint());
                if(number(Db.one("SELECT COUNT(*) n FROM recommendation_assessment_requests").get("n"),true)>=MAX_REQUESTS)throw fail(409,"核对请求审计达到容量上限");
                String receipt=receiptHash(session.uid,request,checked.fingerprint(),payloadHash,id,next);
                Db.exec("INSERT INTO recommendation_assessment_requests VALUES(?,?,?,?,?,?,?)",session.uid,request,checked.fingerprint(),payloadHash,id,next,receipt);
                key(session,checked.context(),checked.teacherId(),checked.request(),true);
                return map("result",stored(checked,0).view(),"current_revision",next,"replayed",false);
            });
        }
    }
    private static Snapshot stored(Key key,long wanted)throws Exception {
        try {
            Map<String,Object> head=Db.one("SELECT * FROM recommendation_assessment_heads WHERE basis_key=?",key.fingerprint());
            if(head==null) {
                if(wanted!=0||Db.one("SELECT request_id FROM recommendation_assessment_requests WHERE basis_key=? FETCH FIRST 1 ROW ONLY",key.fingerprint())!=null)throw corrupt();
                return new Snapshot("",0,null,ZERO,Map.of(),null);
            }
            if(number(head.get("scope_id"),false)!=key.context().scopeId()||!key.context().type().equals(head.get("context_type"))||number(head.get("context_id"),false)!=key.context().id()||!key.context().organization().equals(head.get("organization_code"))||number(head.get("teacher_id"),false)!=key.teacherId()||!hash(canonical(key.request())).equals(head.get("request_hash")))throw corrupt();
            if(!Objects.equals(head.get("teach_date"),key.request().get("date"))||!Objects.equals(head.get("start_time"),key.request().get("start_time"))||!Objects.equals(head.get("end_time"),key.request().get("end_time"))||!Objects.equals(head.get("topic"),key.request().get("topic"))||!Objects.equals(head.get("teaching_place"),key.request().get("teaching_place")))throw corrupt();
            String id=(String)head.get("assessment_id");if(id==null||!id.matches("[a-f0-9-]{36}"))throw corrupt();
            List<Map<String,Object>> rows=Db.query("SELECT * FROM recommendation_assessment_events WHERE assessment_id=? ORDER BY revision LIMIT 201",id);
            if(rows.isEmpty()||rows.size()>MAX_REVISIONS||rows.size()!=number(head.get("revision"),false)||wanted>rows.size())throw corrupt();
            String previous=ZERO;Snapshot selected=null;
            for(int i=0;i<rows.size();i++) {
                Map<String,Object> db=rows.get(i);String payload=(String)db.get("payload");if(payload==null||payload.length()>32768)throw corrupt();
                Map<String,Object> row=Json.parseMap(payload);boolean legacy="RECOMMENDATION-ASSESSMENT-3".equals(row.get("format")),v2=legacy||"RECOMMENDATION-ASSESSMENT-2".equals(row.get("format"));
                Map<String,Object> shape=new LinkedHashMap<>(row);
                if(legacy){if(key.context().sourceVersion().isEmpty()||!key.context().sourceVersion().equals(shape.remove("legacy_source_version")))throw corrupt();}
                else if(!key.context().sourceVersion().isEmpty())throw corrupt();
                if(v2)keys(shape,"format","assessment_id","revision","basis_key","scope_id","organization_code","context_type","context_id","teacher_id","request","facts","actor_account","actor_person","actor_name","created_at","reason","experience_review");
                else keys(shape,"format","assessment_id","revision","basis_key","scope_id","organization_code","context_type","context_id","teacher_id","request","facts","actor_account","actor_person","actor_name","created_at","reason");
                if(number(db.get("revision"),false)!=i+1||number(row.get("revision"),false)!=i+1||!previous.equals(db.get("previous_hash"))||!payload.equals(canonical(row))||!hash(previous+"\n"+payload).equals(db.get("event_hash")))throw corrupt();
                if(!v2&&!"RECOMMENDATION-ASSESSMENT-1".equals(row.get("format"))||!id.equals(row.get("assessment_id"))||!key.fingerprint().equals(row.get("basis_key"))||number(row.get("scope_id"),false)!=key.context().scopeId()||!key.context().organization().equals(row.get("organization_code"))||!key.context().type().equals(row.get("context_type"))||number(row.get("context_id"),false)!=key.context().id()||number(row.get("teacher_id"),false)!=key.teacherId()||!canonical(key.request()).equals(canonical(row.get("request"))))throw corrupt();
                number(row.get("actor_account"),false);text(row.get("actor_person"),128,false);text(row.get("actor_name"),128,false);Instant.parse(text(row.get("created_at"),40,false));text(row.get("reason"),1000,false);
                Facts f=facts(object(row.get("facts")));validateForRequest(f,key.request(),Instant.parse((String)row.get("created_at")));previous=(String)db.get("event_hash");
                // Historical review validity is syntactic and hash-bound only. Current material checks belong to consumption.
                Map<String,Object> review=v2&&row.get("experience_review")!=null?RecommendationExperienceReview.normalize(object(row.get("experience_review"))):null;
                if(review!=null&&!canonical(review).equals(canonical(row.get("experience_review"))))throw corrupt();
                if(wanted==0&&i==rows.size()-1||wanted==i+1)selected=new Snapshot(id,i+1,f,previous,Collections.unmodifiableMap(row),review);
            }
            if(!previous.equals(head.get("head_hash")))throw corrupt();
            List<Map<String,Object>> receipts=Db.query("SELECT * FROM recommendation_assessment_requests WHERE basis_key=? ORDER BY request_id LIMIT 201",key.fingerprint());if(receipts.size()>MAX_REVISIONS)throw corrupt();
            for(Map<String,Object> receipt:receipts) {
                verifyReceipt(receipt);if(!id.equals(receipt.get("assessment_id"))||number(receipt.get("result_revision"),false)>rows.size())throw corrupt();
            }
            if(selected==null)throw corrupt();return selected;
        }catch(Api.ApiException invalid){if(invalid.code==409)throw invalid;throw corrupt();}
        catch(IllegalArgumentException|DateTimeException invalid){throw corrupt();}
    }
    static Map<String,Object> request(Map<String,Object> raw)throws Api.ApiException {
        keys(raw,"topic","course_code","date","start_time","end_time","mode","teaching_place");
        String topic=text(raw.get("topic"),500,false),course=text(raw.get("course_code"),64,true),date=text(raw.get("date"),10,true),start=text(raw.get("start_time"),5,true),end=text(raw.get("end_time"),5,true),mode=text(raw.get("mode"),16,false),place=text(raw.get("teaching_place"),200,true);
        new Request(topic,course,date,start,end,mode,List.of());
        return map("topic",topic,"course_code",course,"date",date,"start_time",start,"end_time",end,"mode",mode,"teaching_place",place);
    }
    static Facts facts(Map<String,Object> raw)throws Api.ApiException {
        keys(raw,"service","availability","available_from","available_until","travel_feasibility","transport_mode","departure_place","teaching_place","leg_minutes","door_to_door_minutes","before_buffer_minutes","after_buffer_minutes","travel_cost","currency","cost_coverage","distance_km","distance_coverage","source_observed_at","valid_until","source_note","source_kind");
        try {
            if(!"STAFF_RECORDED".equals(raw.get("source_kind")))throw fail(400,"核对来源须为操作人明确记录");
            Confirmation service=Confirmation.valueOf(text(raw.get("service"),16,false)),availability=Confirmation.valueOf(text(raw.get("availability"),16,false)),feasibility=Confirmation.valueOf(text(raw.get("travel_feasibility"),16,false));
            Instant from=instant(raw.get("available_from"),true),until=instant(raw.get("available_until"),true),observed=instant(raw.get("source_observed_at"),false),valid=instant(raw.get("valid_until"),false);
            if(from==null!= (until==null)||from!=null&&!from.isBefore(until)||!observed.isBefore(valid))throw fail(400,"核对时间范围无效");
            if(availability==Confirmation.CONFIRMED&&from==null)throw fail(400,"确认完整档期须填写可用窗口");
            String mode=text(raw.get("transport_mode"),16,false);if(!Set.of("LOCAL","RAIL","AIR","OTHER","UNKNOWN").contains(mode))throw fail(400,"交通方式无效");
            Integer leg=minutes(raw.get("leg_minutes")),door=minutes(raw.get("door_to_door_minutes")),before=minutes(raw.get("before_buffer_minutes")),after=minutes(raw.get("after_buffer_minutes"));
            BigDecimal cost=null;Object value=raw.get("travel_cost");if(value!=null){String str=text(value,24,false);if(!str.matches("[0-9]{1,7}(?:\\.[0-9]{1,2})?"))throw fail(400,"交通费用须为明确的非负金额");cost=new BigDecimal(str);}
            String currency=text(raw.get("currency"),3,true),coverage=text(raw.get("cost_coverage"),32,true);if(cost!=null&&(!currency.matches("[A-Z]{3}")||!Set.of("ONE_WAY","ROUND_TRIP","TOTAL_REQUEST").contains(coverage))||cost==null&&(!currency.isEmpty()||!coverage.isEmpty()))throw fail(400,"费用范围或币种须与已知报价一致");
            Long distance=null;Object distanceRaw=raw.get("distance_km");String distanceCoverage=text(raw.get("distance_coverage"),32,true);if(distanceRaw!=null){String km=text(distanceRaw,24,false);if(!km.matches("[0-9]{1,6}(?:\\.[0-9]{1,3})?"))throw fail(400,"距离须为已核对的公里数");distance=new BigDecimal(km).movePointRight(3).longValueExact();if(!distanceCoverage.equals("DEPARTURE_TO_TEACHING"))throw fail(400,"距离口径须为实际出发地至本次授课地点");}else if(!distanceCoverage.isEmpty())throw fail(400,"未知距离不能填写已知口径");
            if(leg!=null&&door!=null&&door<leg)throw fail(400,"门到门时长不能小于车程或航程");
            return new Facts(service,availability,from,until,feasibility,mode,text(raw.get("departure_place"),200,true),text(raw.get("teaching_place"),200,true),leg,door,before,after,cost,currency,coverage,distance,distanceCoverage,observed,valid,text(raw.get("source_note"),1000,false));
        }catch(IllegalArgumentException|DateTimeException bad){throw fail(400,"核对事实枚举或时间格式无效");}
    }
    static void validateForRequest(Facts facts,Map<String,Object> request,Instant now)throws Api.ApiException {
        if(facts.observedAt().isAfter(now)||!facts.validUntil().isAfter(now))throw fail(409,"请填写已经完成且仍在有效期内的核对事实");
        Instant start=window(request,true),end=window(request,false);
        if(start==null||end==null)throw fail(409,"保存本次核对前须明确授课日期和完整时段");
        Instant requiredStart=start,requiredEnd=end;
        if(!"ONLINE".equals(request.get("mode"))){if(facts.beforeBuffer()!=null)requiredStart=start.minusSeconds(facts.beforeBuffer()*60L);if(facts.afterBuffer()!=null)requiredEnd=end.plusSeconds(facts.afterBuffer()*60L);}
        if(facts.availability()==Confirmation.CONFIRMED&&(facts.availableFrom().isAfter(requiredStart)||facts.availableUntil().isBefore(requiredEnd)))throw fail(409,"确认的可用窗口未覆盖本次完整授课及已知出行缓冲");
        if(!Objects.equals(request.get("teaching_place"),facts.teachingPlace()))throw fail(409,"核对目的地与本次请求不一致");
    }
    static Instant window(Map<String,Object> request,boolean start)throws Api.ApiException {
        String date=(String)request.get("date"),time=(String)request.get(start?"start_time":"end_time");
        if(date.isEmpty()||time.isEmpty())return null;
        try{return LocalDateTime.parse(date+"T"+time).atZone(ZONE).toInstant();}catch(DateTimeException invalid){throw fail(400,"授课时窗无效");}
    }
    private static void verifyReceipt(Map<String,Object> row)throws Api.ApiException {
        long account=number(row.get("account_id"),false),revision=number(row.get("result_revision"),false);
        String request=text(row.get("request_id"),96,false),basis=text(row.get("basis_key"),64,false),payload=text(row.get("payload_hash"),64,false),id=text(row.get("assessment_id"),36,false);
        if(!basis.matches("[a-f0-9]{64}")||!payload.matches("[a-f0-9]{64}")||!receiptHash(account,request,basis,payload,id,revision).equals(row.get("receipt_hash")))throw corrupt();
    }
    private static String receiptHash(long account,String request,String basis,String payload,String id,long revision){return hash(canonical(map("account",account,"request",request,"basis",basis,"payload",payload,"assessment",id,"revision",revision)));}
    private static void live(Auth.Session s,boolean write)throws Exception {if(s==null||Auth.current(s)!=s)throw fail(401,"登录已失效");if(write&&!Auth.canWrite(s))throw fail(403,"当前账号不能保存本次核对事实");OrganizationAccessStore.person(s);}
    private static void require(Auth.Session s,String resource,OrganizationAccess.Action action,String org)throws Exception {if(!OrganizationAccessStore.authorize(s,resource,action,org).allowed())throw fail(403,"没有该业务范围的核对权限");}
    private static Integer minutes(Object raw)throws Api.ApiException {if(raw==null)return null;long n=number(raw,true);if(n>10080)throw fail(400,"交通或缓冲时长超出本次核对范围");return (int)n;}
    private static Instant instant(Object value,boolean empty)throws Api.ApiException {if(value==null&&empty)return null;return Instant.parse(text(value,40,false));}
    private static String iso(Instant value){return value==null?null:value.toString();}
    static String canonical(Object value){return TrainingSummariesWorkflow.canonical(value);}
    private static long number(Object raw,boolean zero)throws Api.ApiException {try{if(!(raw instanceof Number))throw fail(400,"编号须为安全整数");long n=new BigDecimal(raw.toString()).longValueExact();if(n<(zero?0:1)||n>9007199254740991L)throw fail(400,"编号范围无效");return n;}catch(ArithmeticException invalid){throw fail(400,"编号须为安全整数");}}
    private static String text(Object value,int max,boolean empty)throws Api.ApiException {if(!(value instanceof String s))throw fail(400,"文字字段无效");try{return bounded(s,max,empty);}catch(IllegalArgumentException bad){throw fail(400,"文字字段无效");}}
    private static void keys(Map<String,Object> value,String...keys)throws Api.ApiException {if(value==null||!value.keySet().equals(Set.of(keys)))throw fail(400,"核对字段不完整或含未知字段");}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value)throws Api.ApiException {if(!(value instanceof Map<?,?>))throw corrupt();return (Map<String,Object>)value;}
    private static List<?> list(Object value)throws Api.ApiException {if(!(value instanceof List<?> l))throw corrupt();return l;}
    private static Api.ApiException corrupt(){return fail(409,"本次核对审计无法完整核验，请联系管理员保留记录检查");}
    private static Api.ApiException fail(int code,String text){return new Api.ApiException(code,text);}
}
