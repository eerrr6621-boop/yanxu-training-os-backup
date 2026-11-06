package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Explicit dispatch-to-standard-course associations. Financial rows are never teaching occurrences. */
public final class CourseActivityIntegration {
    private CourseActivityIntegration() {}
    private static final String ROOT="/api/course-activity", SCHEMA="course_activity_v1";
    private static final String INVALID="授课课程关联历史无法核实，请核对原始资料";
    private static final Set<String> FIELDS=Set.of("scope_id","course_code","expected_version","expected_fact_version","expected_source_version","expected_catalog_version","evidence_note","request_id","confirm");

    public static void init() throws SQLException {
        synchronized(Api.MUTATION_LOCK) {
            if(!Db.get().getAutoCommit())throw new SQLException("授课课程关联初始化不可嵌套事务");
            Db.exec("CREATE TABLE IF NOT EXISTS m04_course_activity_heads(dispatch_id BIGINT PRIMARY KEY REFERENCES dispatches(id),version BIGINT NOT NULL CHECK(version>0),payload CLOB NOT NULL,digest VARCHAR(64) NOT NULL)");
            Db.exec("CREATE TABLE IF NOT EXISTS m04_course_activity_revisions(dispatch_id BIGINT NOT NULL REFERENCES m04_course_activity_heads(dispatch_id),version BIGINT NOT NULL,payload CLOB NOT NULL,digest VARCHAR(64) NOT NULL,PRIMARY KEY(dispatch_id,version))");
            Db.exec("CREATE TABLE IF NOT EXISTS m04_course_activity_requests(account_id BIGINT NOT NULL,request_id VARCHAR(96) NOT NULL,dispatch_id BIGINT NOT NULL REFERENCES dispatches(id),payload CLOB NOT NULL,response CLOB NOT NULL,PRIMARY KEY(account_id,request_id))");
        }
    }

    /** Host calls after its existing save/delete authorization. Preserve classified historical records. */
    public static void guardLegacyMutation(String module,long id,Map<String,Object> body) throws Exception {
        if(id<1||!Set.of("dispatches","dispatches/delete").contains(module))return;
        synchronized(Api.MUTATION_LOCK) {
            // Older isolated host fixtures may not install this optional adapter; then no associations exist.
            if(number(Db.one("SELECT COUNT(*) AS n FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_NAME='M04_COURSE_ACTIVITY_HEADS'").get("n"),true)==0)return;
            if(Db.one("SELECT dispatch_id FROM m04_course_activity_heads WHERE dispatch_id=?",id)==null)return;
            if(module.endsWith("/delete"))fail(409,"该排课已有标准课程关联历史，不能删除；请保留原记录并按原流程调整");
            if(body==null||!body.containsKey("project_id"))return;
            Map<String,Object> old=Db.one("SELECT project_id FROM dispatches WHERE id=?",id);long next=number(body.get("project_id"),false);
            if(old==null||number(old.get("project_id"),false)==next)return;
            SurveySummaryImportsIntegration.Source from=SurveySummaryImportsIntegration.trustedSource(number(old.get("project_id"),false));
            SurveySummaryImportsIntegration.Source to=SurveySummaryImportsIntegration.trustedSource(next);
            if(!from.org().equals(to.org()))fail(409,"该排课已有标准课程关联历史，不能跨机构移动；请保留原记录并另建新排课");
        }
    }

    public static boolean handle(HttpExchange ex,Auth.Session supplied) throws Exception {
        String path=ex.getRequestURI().getPath();if(!path.equals(ROOT)&&!path.startsWith(ROOT+"/"))return false;
        ex.getResponseHeaders().set("Cache-Control","no-store");
        synchronized(Api.MUTATION_LOCK) {
            Auth.Session s=Auth.get(Api.token(ex));if(s==null||s!=supplied)fail(401,"登录会话无效或已失效");session(s);
            String suffix=path.substring(ROOT.length());String[] parts=suffix.split("/",-1);
            if(parts.length==3&&parts[1].equals("scopes")) {
                readMethod(ex);query(ex,Set.of());Api.ok(ex,report(s,number(parts[2],false)));return true;
            }
            if(parts.length==3&&parts[1].equals("dispatches")) {
                readMethod(ex);Map<String,String> q=query(ex,Set.of("scope_id"));if(q.size()!=1)fail(400,"请选择明确课程目录");
                Api.ok(ex,dispatch(s,number(parts[2],false),number(q.get("scope_id"),false)));return true;
            }
            if(parts.length==4&&parts[1].equals("dispatches")&&parts[3].equals("link")) {
                if(!"POST".equals(ex.getRequestMethod()))fail(405,"请求方法不支持");query(ex,Set.of());
                String type=ex.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.split(";",2)[0].trim().equalsIgnoreCase("application/json"))fail(415,"请提交JSON对象");
                Api.ok(ex,link(s,number(parts[2],false),Api.body(ex)));return true;
            }
            fail(404,"授课课程统计接口不存在");return true;
        }
    }

    /** Only existing catalog/read and delivery/read scopes, plus the host's final dispatch visibility. */
    public static Map<String,Object> report(Auth.Session supplied,long scopeId) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            Auth.Session s=session(supplied);List<Map<String,Object>> catalog=CourseCatalogIntegration.readCourses(s,scopeId);
            Map<String,Object> scope=scope(s,scopeId);String org=(String)scope.get("organization_code");permission(s,"delivery.read",org,false);
            Map<String,Map<String,Object>> courses=new LinkedHashMap<>();
            for(Map<String,Object> course:catalog)courses.put((String)course.get("course_code"),courseSummary(course));
            List<Map<String,Object>> unlinked=new ArrayList<>(),notCounted=new ArrayList<>(),occurrences=new ArrayList<>();
            Set<Long> teachers=new HashSet<>();int completed=0;
            List<Map<String,Object>> raw=Db.query("SELECT d.id,d.project_id,d.teacher_id,d.subject,d.teach_date,d.status,p.title AS project_title,p.status AS project_status,t.name AS teacher_name FROM dispatches d JOIN projects p ON p.id=d.project_id JOIN teachers t ON t.id=d.teacher_id ORDER BY d.teach_date DESC,d.id DESC");
            List<Map<String,Object>> visible=WorkflowReadAccessBridge.listRows("dispatches",raw,s,Map.of());
            for(Map<String,Object> row:visible) {
                long id=number(row.get("id"),false),project=number(row.get("project_id"),false);
                Map<String,Object> anchor=Db.one("SELECT w.organization_code FROM projects p JOIN workflow_demands w ON w.demand_id=p.demand_id WHERE p.id=?",project);
                if(anchor==null||!org.equals(anchor.get("organization_code")))continue;
                Context c=context(s,id,row);List<Map<String,Object>> history=history(s,c);Map<String,Object> current=last(history);
                Map<String,Object> occurrence=occurrence(c,current);String reason=associationExclusion(c,current);
                if(reason!=null){occurrence.put("exclusion_reason",reason);notCounted.add(occurrence);continue;}
                completed++;
                if(current==null){unlinked.add(occurrence);continue;}
                Map<String,Object> course=object(current.get("course"));
                if(number(course.get("scope_id"),false)!=scopeId)fail(409,INVALID);
                String code=(String)course.get("course_code");Map<String,Object> summary=courses.computeIfAbsent(code,key->courseSummary(course));
                summary.put("teaching_count",number(summary.get("teaching_count"),true)+1);
                @SuppressWarnings("unchecked") Map<Long,Map<String,Object>> perTeacher=(Map<Long,Map<String,Object>>)summary.get("teacher_index");
                Map<String,Object> teacher=perTeacher.computeIfAbsent(c.teacher,key->values("teacher_id",c.teacher,"teacher_name",c.row.get("teacher_name"),"teaching_count",0L));
                teacher.put("teaching_count",number(teacher.get("teaching_count"),true)+1);teachers.add(c.teacher);occurrences.add(occurrence);
            }
            long unique=0;for(Map<String,Object> course:courses.values()) {
                if(number(course.get("teaching_count"),true)>0)unique++;
                @SuppressWarnings("unchecked") Map<Long,Map<String,Object>> index=(Map<Long,Map<String,Object>>)course.remove("teacher_index");
                course.put("teachers",new ArrayList<>(index.values()));course.put("teacher_count",index.size());
            }
            session(s);return values("schema_version",SCHEMA,"scope_id",scopeId,"organization_code",org,"catalog_version",scope.get("version"),
                "as_of_date",today().toString(),"counting_basis","VERIFIED_COMPLETED_DISPATCH_ID","completed_teaching_count",completed,
                "linked_teaching_count",occurrences.size(),"unlinked_teaching_count",unlinked.size(),"unique_course_count",unique,"actual_teacher_count",teachers.size(),
                "courses",new ArrayList<>(courses.values()),"occurrences",occurrences,"unlinked",unlinked,"not_counted",notCounted,
                "notice","仅统计当前可见且有可信机构来源的授课；缺少来源的历史项目需先核实迁移。每个已完成且已核对的排课记为一次授课，未关联课程单独列示。教师来自实际授课记录，不表示已取得课程认证。");
        }
    }

    public static Map<String,Object> dispatch(Auth.Session supplied,long id,long scopeId) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            Auth.Session s=session(supplied);Context c=context(s,id,null);Map<String,Object> scope=scope(s,scopeId);
            if(!c.org.equals(scope.get("organization_code")))fail(403,"课程目录与授课项目不在同一机构范围");
            List<Map<String,Object>> courses=CourseCatalogIntegration.readCourses(s,scopeId),history=history(s,c);Map<String,Object> current=last(history);
            return values("schema_version",SCHEMA,"scope_id",scopeId,"catalog_version",scope.get("version"),"dispatch",occurrence(c,current),
                "version",history.size(),"fact_version",c.factVersion,"source_version",sourceVersion(c),"current",current,"history",history,"courses",courses,"can_link",canLink(s,c),"exclusion_reason",associationExclusion(c,current));
        }
    }

    /** Explicit user choice only; immutable audit versions, CAS and caller-scoped idempotency. */
    public static Map<String,Object> link(Auth.Session supplied,long id,Map<String,Object> input) throws Exception {
        synchronized(Api.MUTATION_LOCK) {
            return Db.transaction(()->{
                Auth.Session s=session(supplied);if(!input.keySet().equals(FIELDS))fail(400,"授课课程关联字段不完整或包含未允许字段");
                if(!Boolean.TRUE.equals(input.get("confirm")))fail(400,"请明确确认本次授课与所选标准课程的关联");
                long scopeId=number(input.get("scope_id"),false),expected=number(input.get("expected_version"),true),factVersion=number(input.get("expected_fact_version"),true),catalogVersion=number(input.get("expected_catalog_version"),false);
                String courseCode=text(input.get("course_code"),64),note=text(input.get("evidence_note"),1000),request=text(input.get("request_id"),96);
                String expectedSource=text(input.get("expected_source_version"),64);if(!expectedSource.matches("[0-9a-f]{64}"))fail(400,"授课来源版本无效");
                if(!request.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}"))fail(400,"请求编号格式无效");
                Context c=context(s,id,null);permission(s,"delivery.write",c.org,true);
                Map<String,Object> scope=scope(s,scopeId);if(!c.org.equals(scope.get("organization_code")))fail(403,"课程目录与授课项目不在同一机构范围");
                List<Map<String,Object>> old=history(s,c);Map<String,Object> prior=last(old);
                String actor=OrganizationAccessStore.person(s).personCode(),payload=canonical(input);
                Map<String,Object> replay=Db.one("SELECT * FROM m04_course_activity_requests WHERE account_id=? AND request_id=?",s.uid,request);
                if(replay!=null) {
                    if(number(replay.get("dispatch_id"),false)!=id||!payload.equals(replay.get("payload")))fail(409,"同一请求编号已用于不同内容");
                    Map<String,Object> receipt=json(replay.get("response"));long v=number(receipt.get("version"),false);
                    if(!receipt.keySet().equals(Set.of("version","record_digest"))||v!=expected+1||v>old.size())fail(409,INVALID);Map<String,Object> saved=old.get((int)v-1),savedCourse=object(saved.get("course"));
                    if(!actor.equals(saved.get("actor_person"))||number(saved.get("actor_account"),false)!=s.uid||!digest(saved).equals(receipt.get("record_digest"))
                        ||number(savedCourse.get("scope_id"),false)!=scopeId||number(savedCourse.get("version"),false)!=catalogVersion||!courseCode.equals(savedCourse.get("course_code"))
                        ||number(saved.get("fact_version"),true)!=factVersion||!expectedSource.equals(saved.get("selection_source_version"))||!note.equals(saved.get("evidence_note")))fail(409,INVALID);
                    return values("status","ALREADY_LINKED","dispatch_id",id,"version",v,"current_version",old.size(),"course",saved.get("course"));
                }
                if(!canLink(s,c))fail(409,"当前授课状态不支持关联；已结束项目需已有完成且核对的实际授课");
                if(expected!=old.size()||factVersion!=c.factVersion||!expectedSource.equals(sourceVersion(c))||catalogVersion!=number(scope.get("version"),true))fail(409,"授课、课程目录或关联版本已变化，请刷新后重新选择");
                Map<String,Object> course=CourseCatalogIntegration.readCourse(s,scopeId,courseCode);
                if(!Boolean.TRUE.equals(course.get("active")))fail(409,"所选标准课程已停用，请核对后选择");
                if(prior!=null&&sourceMatches(c,prior)&&number(object(prior.get("course")).get("scope_id"),false)==scopeId&&courseCode.equals(object(prior.get("course")).get("course_code")))fail(409,"本次授课已关联此标准课程，无需重复保存");
                long version=expected+1;Map<String,Object> record=values("schema_version",SCHEMA,"dispatch_id",id,"version",version,"source",c.source,
                    "fact_version",c.factVersion,"selection_source_version",expectedSource,"course",course,"evidence_note",note,"actor_account",s.uid,"actor_person",actor,
                    "identity_version",OrganizationAccessStore.configuration().version(),"created_at",Instant.now().toString(),"previous_digest",prior==null?null:digest(prior));
                String saved=canonical(record),hash=digest(record);
                if(prior==null)Db.exec("INSERT INTO m04_course_activity_heads VALUES(?,?,?,?)",id,version,saved,hash);
                else try(PreparedStatement p=Db.get().prepareStatement("UPDATE m04_course_activity_heads SET version=?,payload=?,digest=? WHERE dispatch_id=? AND version=?")) {
                    p.setLong(1,version);p.setString(2,saved);p.setString(3,hash);p.setLong(4,id);p.setLong(5,expected);if(p.executeUpdate()!=1)fail(409,"关联已更新，请刷新");
                }
                Db.exec("INSERT INTO m04_course_activity_revisions VALUES(?,?,?,?)",id,version,saved,hash);
                Db.exec("INSERT INTO m04_course_activity_requests VALUES(?,?,?,?,?)",s.uid,request,id,payload,canonical(values("version",version,"record_digest",hash)));
                return values("status","LINKED","dispatch_id",id,"version",version,"current_version",version,"course",course);
            });
        }
    }

    /** Internal confirmation check; existing dispatch write scope plus catalog read, no new grant. */
    static Map<String,Object> confirmationCourse(Auth.Session supplied,Map<String,Object> dispatch) throws Exception {
        Auth.Session s=session(supplied);long id=number(dispatch.get("id"),false),project=number(dispatch.get("project_id"),false),teacher=number(dispatch.get("teacher_id"),false);
        if(!Auth.canWrite(s))fail(403,"当前账号不能确认排课");
        WorkflowIntegration.requireProjectAccess(project,s,true);
        if(Db.one("SELECT dispatch_id FROM m04_course_activity_heads WHERE dispatch_id=?",id)==null)return null;
        String org=SurveySummaryImportsIntegration.trustedSource(project).org();
        Map<String,Object> fact=Db.one("SELECT revision FROM m05_delivery_facts WHERE dispatch_id=?",id);
        long revision=fact==null?0:number(fact.get("revision"),false);
        Map<String,Object> source=values("dispatch_id",id,"project_id",project,"teacher_id",teacher,"organization_code",org,"teach_date",dispatch.get("teach_date"));
        Context context=new Context(id,project,teacher,org,revision,dispatch,source,null);
        Map<String,Object> current=last(history(s,context));
        if(current==null||!sourceMatches(context,current))fail(409,"原标准课程关联的授课来源已变化，请先在原关联入口明确重新核对");
        return new LinkedHashMap<>(object(current.get("course")));
    }

    private record Context(long id,long project,long teacher,String org,long factVersion,Map<String,Object> row,Map<String,Object> source,Map<String,Object> fact) {}
    private static Context context(Auth.Session s,long id,Map<String,Object> visible) throws Exception {
        if(visible==null) {
            List<Map<String,Object>> rows=Db.query("SELECT d.id,d.project_id,d.teacher_id,d.subject,d.teach_date,d.status,p.title AS project_title,p.status AS project_status,t.name AS teacher_name FROM dispatches d JOIN projects p ON p.id=d.project_id JOIN teachers t ON t.id=d.teacher_id WHERE d.id=?",id);
            List<Map<String,Object>> allowed=WorkflowReadAccessBridge.listRows("dispatches",rows,s,Map.of());
            if(allowed.isEmpty())fail(403,"授课不存在或不在当前可读取范围");visible=allowed.get(0);
        }
        long project=number(visible.get("project_id"),false),teacher=number(visible.get("teacher_id"),false);
        Map<String,Object> chain=DeliverySettlementIntegration.codingProjectSource(s,project);String org=(String)chain.get("organization_code");
        Map<String,Object> head=Db.one("SELECT revision FROM m05_delivery_facts WHERE dispatch_id=?",id);long version=head==null?0:number(head.get("revision"),false);
        Map<String,Object> history=DeliverySettlementIntegration.history(s,id,version,1,1);List<Map<String,Object>> items=rows(history.get("items"));
        Map<String,Object> source=values("dispatch_id",id,"project_id",project,"teacher_id",teacher,"organization_code",org,"teach_date",visible.get("teach_date"));
        return new Context(id,project,teacher,org,version,visible,source,items.isEmpty()?null:items.get(0));
    }
    private static List<Map<String,Object>> history(Auth.Session s,Context c) throws Exception {
        Map<String,Object> extent=Db.one("SELECT COUNT(*) AS n,COALESCE(SUM(CHAR_LENGTH(payload)),0) AS chars FROM m04_course_activity_revisions WHERE dispatch_id=?",c.id);
        Map<String,Object> head=Db.one("SELECT version,CHAR_LENGTH(payload) AS chars FROM m04_course_activity_heads WHERE dispatch_id=?",c.id);
        long count=number(extent.get("n"),true);if(count>1000||number(extent.get("chars"),true)>8000000||head!=null&&number(head.get("chars"),true)>20000)fail(409,"关联历史超过核验容量，请联系管理员核对");
        if(count!=(head==null?0:number(head.get("version"),false)))fail(409,INVALID);if(count==0)return new ArrayList<>();
        head=Db.one("SELECT * FROM m04_course_activity_heads WHERE dispatch_id=?",c.id);List<Map<String,Object>> result=new ArrayList<>();String previous=null;
        for(Map<String,Object> row:Db.query("SELECT * FROM m04_course_activity_revisions WHERE dispatch_id=? ORDER BY version",c.id)) {
            Map<String,Object> r=json(row.get("payload"));long version=result.size()+1L;
            if(!r.keySet().equals(Set.of("schema_version","dispatch_id","version","source","fact_version","selection_source_version","course","evidence_note","actor_account","actor_person","identity_version","created_at","previous_digest"))
                ||!SCHEMA.equals(r.get("schema_version"))||number(row.get("version"),false)!=version||number(r.get("version"),false)!=version||number(r.get("dispatch_id"),false)!=c.id
                ||!Objects.equals(previous,r.get("previous_digest"))||!digest(r).equals(row.get("digest")))fail(409,INVALID);
            Map<String,Object> oldSource=object(r.get("source"));
            if(!oldSource.keySet().equals(Set.of("dispatch_id","project_id","teacher_id","organization_code","teach_date"))||number(oldSource.get("dispatch_id"),false)!=c.id)fail(409,INVALID);
            number(oldSource.get("project_id"),false);number(oldSource.get("teacher_id"),false);text(oldSource.get("organization_code"),128);
            if(oldSource.get("teach_date")!=null)try{LocalDate.parse(text(oldSource.get("teach_date"),32));}catch(java.time.format.DateTimeParseException bad){fail(409,INVALID);}
            Map<String,Object> course=object(r.get("course"));Map<String,Object> verified=CourseCatalogIntegration.readCourse(s,number(course.get("scope_id"),false),number(course.get("version"),false),text(course.get("course_code"),64));
            if(!oldSource.get("organization_code").equals(course.get("organization_code"))||!canonical(verified).equals(canonical(course))||number(r.get("fact_version"),true)>c.factVersion)fail(409,INVALID);
            number(r.get("actor_account"),false);text(r.get("actor_person"),128);text(r.get("identity_version"),128);text(r.get("evidence_note"),1000);
            if(!text(r.get("selection_source_version"),64).matches("[0-9a-f]{64}"))fail(409,INVALID);
            try{if(Instant.parse(text(r.get("created_at"),40)).isAfter(Instant.now()))fail(409,INVALID);}catch(java.time.format.DateTimeParseException bad){fail(409,INVALID);}
            previous=(String)row.get("digest");result.add(r);
        }
        if(result.size()!=count||!previous.equals(head.get("digest"))||!canonical(last(result)).equals(canonical(json(head.get("payload")))))fail(409,INVALID);return result;
    }
    private static Map<String,Object> courseSummary(Map<String,Object> course) {
        return values("scope_id",course.get("scope_id"),"course_code",course.get("course_code"),"course_name",course.get("course_name"),"active",course.get("active"),"teaching_count",0L,"teacher_index",new LinkedHashMap<Long,Map<String,Object>>());
    }
    private static String sourceVersion(Context c) {return digest(values("source",c.source,"subject",c.row.get("subject"),"project_status",c.row.get("project_status"),"dispatch_status",c.row.get("status"),"fact_version",c.factVersion));}
    private static Map<String,Object> occurrence(Context c,Map<String,Object> current) {
        Map<String,Object> course=current==null?null:objectUnchecked(current.get("course"));
        return values("dispatch_id",c.id,"project_id",c.project,"project_title",c.row.get("project_title"),"teacher_id",c.teacher,"teacher_name",c.row.get("teacher_name"),
            "subject",c.row.get("subject"),"teach_date",c.row.get("teach_date"),"status",c.row.get("status"),"fact_version",c.factVersion,
            "association_version",current==null?0:current.get("version"),"association_status",current==null?"UNLINKED":sourceMatches(c,current)?"LINKED":"SOURCE_CHANGED","course",course);
    }
    private static boolean sourceMatches(Context c,Map<String,Object> record){return canonical(c.source).equals(canonical(record.get("source")));}
    private static String associationExclusion(Context c,Map<String,Object> current){return current!=null&&!sourceMatches(c,current)?"关联后排课来源已变化，请重新核对并明确确认标准课程":exclusion(c);}
    private static String exclusion(Context c) {
        if(!"已完成".equals(c.row.get("status")))return "排课尚未完成或已拒绝";
        if(c.fact==null)return "尚无可信授课事实";
        if(c.fact.get("verification")==null||c.fact.get("actual_minutes")==null||c.fact.get("actual_hours")==null)return "实际授课尚未完整核对";
        try {if(LocalDate.parse((String)c.row.get("teach_date")).isAfter(today()))return "授课日期尚未到达";}
        catch(RuntimeException bad){return "授课日期无法核实";}return null;
    }
    private static boolean canLink(Auth.Session s,Context c) {
        String status=String.valueOf(c.row.get("project_status"));
        boolean editable=Set.of("待启动","进行中").contains(status)||Set.of("已完成","已归档").contains(status)&&exclusion(c)==null;
        return editable&&!"已拒绝".equals(c.row.get("status"))&&OrganizationAccessStore.authorize(s,"delivery.write",OrganizationAccess.Action.HANDLE,c.org).allowed();
    }
    private static Map<String,Object> scope(Auth.Session s,long id) throws Exception {
        Map<String,Object> scope=Db.one("SELECT scope_id,organization_code,version FROM m04_catalog_scopes WHERE scope_id=?",id);if(scope==null)fail(404,"课程目录不存在");
        permission(s,"catalog.read",(String)scope.get("organization_code"),false);return scope;
    }
    private static Auth.Session session(Auth.Session s) throws Exception {if(s==null||Auth.current(s)!=s)fail(401,"登录会话无效或已失效");OrganizationAccessStore.person(s);return s;}
    private static void permission(Auth.Session s,String resource,String org,boolean write) throws Exception {if(!OrganizationAccessStore.authorize(s,resource,write?OrganizationAccess.Action.HANDLE:OrganizationAccess.Action.VIEW,org).allowed())fail(403,"没有当前机构的读取或办理权限");}
    private static void readMethod(HttpExchange ex) throws Exception {if(!Set.of("GET","HEAD").contains(ex.getRequestMethod()))fail(405,"请求方法不支持");}
    private static Map<String,String> query(HttpExchange ex,Set<String> allowed) throws Exception {
        String raw=ex.getRequestURI().getRawQuery();Map<String,String> q=new LinkedHashMap<>();if(raw==null)return q;if(raw.isEmpty()||raw.length()>2048)fail(400,"查询参数无效");
        for(String pair:raw.split("&",-1)) {String[] p=pair.split("=",-1);if(p.length!=2)fail(400,"查询参数无效");String key,value;
            try{key=URLDecoder.decode(p[0],StandardCharsets.UTF_8);value=URLDecoder.decode(p[1],StandardCharsets.UTF_8);}catch(IllegalArgumentException bad){fail(400,"查询参数无效");return q;}
            if(!allowed.contains(key)||q.containsKey(key)||value.isEmpty())fail(400,"查询参数未知或重复");q.put(key,value);
        }return q;
    }
    private static long number(Object value,boolean zero) throws Exception {try{if(!(value instanceof Number)&&!(value instanceof String))throw new IllegalArgumentException();long n=new BigDecimal(value.toString()).longValueExact();if(n<(zero?0:1)||n>9007199254740991L)throw new IllegalArgumentException();return n;}catch(Exception bad){throw new Api.ApiException(400,"记录编号或版本须为安全整数");}}
    private static String text(Object value,int limit) throws Exception {if(!(value instanceof String s)||s.isBlank()||s.length()>limit)fail(400,"说明或编码为空或超出长度限制");return (String)value;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) throws Exception {if(!(value instanceof Map<?,?>))fail(409,INVALID);return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static Map<String,Object> objectUnchecked(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    private static Map<String,Object> json(Object value) throws Exception {if(!(value instanceof String s)||s.length()>20000)fail(409,INVALID);try{return object(Json.parse((String)value));}catch(RuntimeException bad){throw new Api.ApiException(409,INVALID);}}
    private static Map<String,Object> last(List<Map<String,Object>> list){return list.isEmpty()?null:list.get(list.size()-1);}
    private static Map<String,Object> values(Object... pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    private static LocalDate today(){return LocalDate.now(ZoneId.of("Asia/Shanghai"));}
    private static Object sorted(Object value){if(value instanceof Number n)return new BigDecimal(n.toString()).stripTrailingZeros();if(value instanceof Map<?,?> m){Map<String,Object> out=new TreeMap<>();m.forEach((k,v)->out.put(k.toString(),sorted(v)));return out;}if(value instanceof List<?> list)return list.stream().map(CourseActivityIntegration::sorted).toList();return value;}
    private static String canonical(Object value){return Json.write(sorted(value));}
    private static String digest(Object value){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception impossible){throw new IllegalStateException(impossible);}}
    private static void fail(int status,String message) throws Api.ApiException {throw new Api.ApiException(status,message);}
}
