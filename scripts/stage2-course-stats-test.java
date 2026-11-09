package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Fresh synthetic H2, real Auth/M01, no listener/network; never opens production data. */
final class Stage2CourseStatsTest {
    private static final String PASSWORD="SYNTHETIC-COURSE-ACTIVITY-ONLY", DATE=LocalDate.now().minusDays(1).toString();
    private static Auth.Session admin,worker,reader,other,denied,scheduler;private static String schedulerToken;private static String token;private static long scope,otherScope;private static int checks,sequence;
    interface Work {void run() throws Exception;}
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void rejects(int status,Work work,String label)throws Exception {long before=Db.count("m04_course_activity_revisions");try{work.run();throw new AssertionError(label+" accepted");}catch(Api.ApiException e){check(e.code==status,label+" status="+e.code+" "+e.getMessage());}check(before==Db.count("m04_course_activity_revisions"),label+" leaves audit unchanged");}
    private static Map<String,Object> map(Object...pairs){Map<String,Object> m=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    @SuppressWarnings("unchecked")private static Map<String,Object> object(Object v){return (Map<String,Object>)v;}
    @SuppressWarnings("unchecked")private static List<Map<String,Object>> rows(Object v){return (List<Map<String,Object>>)v;}
    private static long n(Object v){return ((Number)v).longValue();}
    private static Map<String,Object> copy(Map<String,Object> v){return Json.parseMap(Json.write(v));}
    private static Configuration config(){
        Set<String> roles=Set.of("ALL","READ","DENIED","SCHED");RelationRule optional=new RelationRule(false,roles,false,false);List<Grant> grants=new ArrayList<>();
        for(String role:List.of("ALL","READ","DENIED"))for(String resource:List.of("catalog.read","delivery.read"))grants.add(new Grant(role+resource,role,resource,Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        for(String resource:List.of("catalog.manage","delivery.write","delivery.verify"))grants.add(new Grant(resource,"ALL",resource,Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        for(String resource:List.of("demand.read","demand.write","delivery.read","delivery.write","catalog.read"))grants.add(new Grant("SCHED-"+resource,"SCHED",resource,resource.endsWith(".read")?Action.VIEW:Action.HANDLE,Effect.ALLOW,resource.startsWith("demand.")?Scope.NAMED_ORGS:Scope.OWN_ORG,resource.startsWith("demand.")?Set.of("001","002"):Set.of()));
        grants.add(new Grant("EXPLICIT-DEMAND-DENY","DENIED","demand.read",Action.VIEW,Effect.DENY,Scope.OWN_ORG,Set.of()));
        return new Configuration("SYNTHETIC-ACTIVITY-1",new CodeRules("[0-9]{3}","[0-9]{4}","[A-Z]+"),roles,List.of(new Organization("001",null,true),new Organization("002",null,true)),
            List.of(new Person("0002","001",Set.of(),null,null,Set.of("ALL"),true),new Person("0003","001",Set.of(),null,null,Set.of("READ"),true),new Person("0004","002",Set.of(),null,null,Set.of("ALL"),true),new Person("0005","001",Set.of(),null,null,Set.of("DENIED"),true),new Person("0006","001",Set.of(),null,null,Set.of("SCHED"),true)),
            roles.stream().map(r->new RoleRelations(r,optional,optional)).toList(),List.of(new AccountBinding(2,"0002",true),new AccountBinding(3,"0003",true),new AccountBinding(4,"0004",true),new AccountBinding(5,"0005",true),new AccountBinding(6,"0006",true)),grants);
    }
    private static void setup(Path data)throws Exception {
        if(!Files.isDirectory(data)||Files.isSymbolicLink(data))throw new IllegalArgumentException("Existing fresh data directory required");try(var f=Files.list(data)){if(f.findAny().isPresent())throw new IllegalArgumentException("Data must be empty");}System.setProperty("data.dir",data.toString());
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64),password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");FirstBindStore.init();TrustedDevices.init();
        Db.exec("CREATE TABLE demands(id BIGINT PRIMARY KEY,title VARCHAR(200),status VARCHAR(32))");
        Db.exec("CREATE TABLE projects(id BIGINT PRIMARY KEY,demand_id BIGINT,title VARCHAR(200),status VARCHAR(32),hours DOUBLE)");
        Db.exec("CREATE TABLE teachers(id BIGINT PRIMARY KEY,name VARCHAR(64),status VARCHAR(32),teacher_level VARCHAR(120),base_city VARCHAR(120),fee_rate DOUBLE)");
        Db.exec("CREATE TABLE dispatches(id BIGINT PRIMARY KEY,project_id BIGINT,teacher_id BIGINT,subject VARCHAR(200),teach_date VARCHAR(32),hours DOUBLE,status VARCHAR(32))");
        Db.exec("CREATE TABLE fees(id BIGINT PRIMARY KEY,project_id BIGINT,teacher_id BIGINT,amount DOUBLE,status VARCHAR(32))");
        for(int i=1;i<=6;i++)Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)",i,"activity-"+i,Auth.hash(PASSWORD),"SYNTHETIC "+i,i==1?"admin":i==6?"manager":"viewer");
        OrganizationAccessStore.init();WorkflowIntegration.init();NotificationChannelsIntegration.init();CourseCatalogIntegration.init();DeliverySettlementIntegration.init();CourseActivityIntegration.init();
        admin=Auth.get(Auth.login("activity-1",PASSWORD));OrganizationAccessStore.publish(admin,null,config());token=Auth.login("activity-2",PASSWORD);worker=Auth.get(token);reader=Auth.get(Auth.login("activity-3",PASSWORD));other=Auth.get(Auth.login("activity-4",PASSWORD));denied=Auth.get(Auth.login("activity-5",PASSWORD));schedulerToken=Auth.login("activity-6",PASSWORD);scheduler=Auth.get(schedulerToken);
        scope=n(CourseCatalogIntegration.registerScope(worker,"001").get("scope_id"));otherScope=n(CourseCatalogIntegration.registerScope(other,"002").get("scope_id"));
        for(int i=1;i<=2;i++){Db.exec("INSERT INTO demands VALUES(?,?,'团队已受理')",i,"SYNTHETIC DEMAND "+i);Db.exec("INSERT INTO projects VALUES(?,?,?,'进行中',0)",i+9,i,"SYNTHETIC PROJECT "+i);Db.exec("INSERT INTO workflow_demands VALUES(?,1,1,FALSE,'direct',?,'0002','{}','{}','SYNTHETIC')",i,i==1?"001":"002");Db.exec("INSERT INTO workflow_acceptances VALUES(?,?,'001','0002',1,'2026-01-01T00:00:00Z')",i,i+9);}
        Db.exec("INSERT INTO teachers VALUES(20,'合成教师甲','在库',NULL,NULL,0)");Db.exec("INSERT INTO teachers VALUES(21,'合成教师乙','在库',NULL,NULL,0)");
        for(int id:List.of(101,102,103,104,105,106,107,108,201))Db.exec("INSERT INTO dispatches VALUES(?,?,?,?,?,0,?)",id,id==201?11:10,id==103?21:20,"同名主题",DATE,id==105?"已拒绝":id==108?"已完成":"已确认");
        publish(0,List.of(course("001","同名课程",true),course("002","同名课程",true),course("003","未使用课程",true)));
        for(String field:List.of("start_time","end_time","venue","confirm_deadline","material_status","sent_at","confirmed_at","msg_log","remark"))Db.exec("ALTER TABLE dispatches ADD COLUMN "+field+" VARCHAR(2000)");
        Db.exec("INSERT INTO dispatches(id,project_id,teacher_id,subject,teach_date,hours,status) VALUES(109,10,20,'待发送可合法变更',?,1,'待发送')",DATE);
        check(Db.count("m04_teacher_bindings")==0,"activity uses original teacher IDs without fabricated business codes or certifications");
        for(int id:List.of(101,102,103,104,106,107)){save(id);verify(id);if(id!=106)Db.exec("UPDATE dispatches SET status='已完成' WHERE id=?",id);}
        check(Db.get().getMetaData().getURL().contains(data.resolve("training").toString()),"exact isolated H2 path");
    }
    private static Map<String,Object> course(String code,String name,boolean active){return map("course_code",code,"course_name",name,"active",active);}
    private static Object call(boolean catalog,String method,String path,Map<String,Object> body)throws Exception {Exchange ex=new Exchange(method,path,token,body);check(catalog?CourseCatalogIntegration.handle(ex,worker):CourseActivityIntegration.handle(ex,worker),"route handled");Object response=ex.getAttribute("com.training.Api.response");var field=response.getClass().getDeclaredField("body");field.setAccessible(true);return Json.parseMap(new String((byte[])field.get(response),StandardCharsets.UTF_8)).get("data");}
    private static void publish(long version,List<Map<String,Object>> courses)throws Exception {Map<String,Object> p=object(call(true,"POST","/api/course-catalog/scopes/"+scope+"/courses-preview",map("expected_version",version,"catalog_version","SYNTHETIC-CATALOG-"+(version+1),"courses",courses,"change_comment","合成标准课程变更")));check(Boolean.TRUE.equals(p.get("ready")),"real course preview ready");call(true,"POST","/api/course-catalog/scopes/"+scope+"/confirm",map("expected_version",version,"batch_id",p.get("batch_id"),"confirm",true));}
    private static long factVersion(long id)throws Exception {Map<String,Object> r=Db.one("SELECT revision FROM m05_delivery_facts WHERE dispatch_id=?",id);return r==null?0:n(r.get("revision"));}
    private static void save(long id)throws Exception {DeliverySettlementIntegration.mutate("save",worker,map("dispatch_id",id,"expected_version",factVersion(id),"request_id","SYNTHETIC-SAVE-"+(++sequence),"actual_minutes","60"));}
    private static void verify(long id)throws Exception {DeliverySettlementIntegration.mutate("verify",worker,map("dispatch_id",id,"expected_version",factVersion(id),"request_id","SYNTHETIC-VERIFY-"+(++sequence),"evidence_code","SYNTHETIC-EVIDENCE"));}
    private static Map<String,Object> command(long id,String code)throws Exception {Map<String,Object> d=CourseActivityIntegration.dispatch(worker,id,scope);return map("scope_id",scope,"course_code",code,"expected_version",d.get("version"),"expected_fact_version",d.get("fact_version"),"expected_source_version",d.get("source_version"),"expected_catalog_version",d.get("catalog_version"),"evidence_note","人工核对合成授课资料","request_id","SYNTHETIC-LINK-"+(++sequence),"confirm",true);}
    private static Map<String,Object> report()throws Exception {long before=Db.count("m04_course_activity_revisions");Map<String,Object> r=CourseActivityIntegration.report(worker,scope);check(before==Db.count("m04_course_activity_revisions"),"report writes no activity revisions");return r;}
    private static Map<String,Object> courseRow(Map<String,Object> report,String code){return rows(report.get("courses")).stream().filter(c->code.equals(c.get("course_code"))).findFirst().orElseThrow();}
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("fresh synthetic directory required");setup(Path.of(args[0]).toAbsolutePath());
        Map<String,Object> initial=report();check(n(initial.get("completed_teaching_count"))==5&&n(initial.get("unlinked_teaching_count"))==5&&n(initial.get("unique_course_count"))==0,"unlinked completed teaching explicit, no subject matching");check(rows(initial.get("not_counted")).size()==4,"pending, rejected and missing-fact explicit exclusions");
        check(rows(initial.get("not_counted")).stream().anyMatch(x->n(x.get("dispatch_id"))==108&&x.get("exclusion_reason").toString().contains("可信授课事实")),"completed flag without actual fact is explicitly excluded");
        Map<String,Object> staleSource=command(108,"001");Db.exec("UPDATE dispatches SET subject='更新后的主题' WHERE id=108");rejects(409,()->CourseActivityIntegration.link(worker,108,staleSource),"stale dispatch source with zero fact version");Db.exec("UPDATE dispatches SET subject='同名主题' WHERE id=108");
        CourseActivityIntegration.link(worker,109,command(109,"001"));
        Exchange edit=new Exchange("POST","/api/dispatches",schedulerToken,map("id",109,"teacher_id",21,"teach_date",LocalDate.now().toString()));new Api().handle(edit);
        Map<String,Object> edited=Json.parseMap(edit.response.toString(StandardCharsets.UTF_8));check(edit.status==200&&n(edited.get("code"))==0,"original Api.handle legally changes pending teacher/date: "+edited);
        Map<String,Object> moved=CourseActivityIntegration.dispatch(worker,109,scope);check("SOURCE_CHANGED".equals(object(moved.get("dispatch")).get("association_status")),"legal source change marked for explicit recheck, not corrupt audit");
        check(rows(report().get("not_counted")).stream().anyMatch(x->n(x.get("dispatch_id"))==109&&x.get("exclusion_reason").toString().contains("来源已变化")),"source-changed association excluded with recoverable reason");
        CourseActivityIntegration.link(worker,109,command(109,"001"));Map<String,Object> recovered=CourseActivityIntegration.dispatch(worker,109,scope);
        check("LINKED".equals(object(recovered.get("dispatch")).get("association_status"))&&rows(recovered.get("history")).size()==2,"same course explicitly reconfirmed after legal change with full history");
        check(n(object(rows(recovered.get("history")).get(0).get("source")).get("teacher_id"))==20&&n(object(rows(recovered.get("history")).get(1).get("source")).get("teacher_id"))==21,"old teacher/date snapshot preserved, current teacher captured independently");
        Exchange deletion=new Exchange("POST","/api/dispatches/delete",schedulerToken,map("id",109));new Api().handle(deletion);check(deletion.status==409&&deletion.response.toString(StandardCharsets.UTF_8).contains("关联历史"),"original authorized delete gives explicit 409 instead of FK/500");
        Exchange migration=new Exchange("POST","/api/dispatches",schedulerToken,map("id",109,"project_id",11));new Api().handle(migration);check(migration.status==409&&migration.response.toString(StandardCharsets.UTF_8).contains("跨机构"),"authorized cross-organization move blocked to retain historical scope");
        check(n(Db.one("SELECT project_id FROM dispatches WHERE id=109").get("project_id"))==10,"rejected cross-scope/delete preserve original dispatch");
        Map<String,Object> first=command(101,"001");CourseActivityIntegration.link(worker,101,first);CourseActivityIntegration.link(worker,102,command(102,"001"));CourseActivityIntegration.link(worker,103,command(103,"001"));
        Map<String,Object> r=report();check(n(r.get("linked_teaching_count"))==3&&n(r.get("unique_course_count"))==1,"one standard course taught on three occasions counts one distinct course");check(n(r.get("actual_teacher_count"))==2&&n(courseRow(r,"001").get("teacher_count"))==2,"same teacher deduped across sessions, actual co-teachers kept");
        check(n(rows(courseRow(r,"001").get("teachers")).get(0).get("teaching_count"))>0,"teacher per-course occurrences explicit");
        check("ALREADY_LINKED".equals(CourseActivityIntegration.link(worker,101,first).get("status"))&&Db.count("m04_course_activity_revisions")==5,"request replay idempotent");
        Map<String,Object> changed=copy(first);changed.put("course_code","002");rejects(409,()->CourseActivityIntegration.link(worker,101,changed),"request ID cannot be reused with a different selection");
        Map<String,Object> stale=command(104,"001");stale.put("expected_fact_version",0);rejects(409,()->CourseActivityIntegration.link(worker,104,stale),"stale fact version");
        Map<String,Object> noConfirm=command(104,"001");noConfirm.put("confirm",false);rejects(400,()->CourseActivityIntegration.link(worker,104,noConfirm),"explicit confirmation");
        CourseActivityIntegration.link(worker,102,command(102,"002"));r=report();check(n(r.get("linked_teaching_count"))==3&&n(r.get("unique_course_count"))==2&&n(courseRow(r,"001").get("teaching_count"))==2,"correction reclassifies one occurrence, does not add occurrence");
        Db.exec("INSERT INTO fees VALUES(1,10,20,100,'已支付')");Db.exec("INSERT INTO fees VALUES(2,10,20,-50,'退款')");Db.exec("UPDATE fees SET amount=999999 WHERE id=1");check(n(report().get("linked_teaching_count"))==3,"payment/refund/accrual changes never add teaching occurrences");
        save(101);r=report();check(n(r.get("linked_teaching_count"))==2&&rows(r.get("not_counted")).stream().anyMatch(x->n(x.get("dispatch_id"))==101&&x.get("exclusion_reason").toString().contains("核对")),"new unverified delivery fact explicitly excluded, not silently counted");verify(101);check(n(report().get("linked_teaching_count"))==3,"verification restores one occurrence not new occurrence");
        publish(1,List.of(course("001","更名后的课程甲",false),course("002","同名课程",true),course("003","未使用课程",true)));
        r=report();check(n(courseRow(r,"001").get("teaching_count"))==2&&"更名后的课程甲".equals(courseRow(r,"001").get("course_name")),"renamed inactive course retains stable identity and teaching history");
        check("同名课程".equals(object(object(CourseActivityIntegration.dispatch(worker,101,scope).get("current")).get("course")).get("course_name")),"original course snapshot remains historical");
        rejects(409,()->CourseActivityIntegration.link(worker,104,command(104,"001")),"inactive course cannot be newly selected");
        Db.exec("UPDATE projects SET status='已完成' WHERE id=10");CourseActivityIntegration.link(worker,104,command(104,"002"));check(n(report().get("linked_teaching_count"))==4,"completed project permits authorized backfill of actual teaching classification");
        Db.exec("UPDATE projects SET status='已归档' WHERE id=10");CourseActivityIntegration.link(worker,107,command(107,"002"));check(n(report().get("linked_teaching_count"))==5,"archived project permits authorized backfill without changing original facts");
        rejects(409,()->CourseActivityIntegration.link(worker,106,command(106,"002")),"archived uncompleted dispatch cannot be retrospectively classified");
        rejects(403,()->CourseActivityIntegration.report(other,scope),"cross organization catalog");rejects(403,()->CourseActivityIntegration.dispatch(worker,201,scope),"cross organization project");
        Map<String,Object> readonly=command(101,"002");rejects(403,()->CourseActivityIntegration.link(reader,101,readonly),"read-only user cannot write");check(n(CourseActivityIntegration.report(reader,scope).get("linked_teaching_count"))==5,"delivery and catalog reader needs no new demand grant");
        check(n(CourseActivityIntegration.report(denied,scope).get("linked_teaching_count"))==0,"explicit demand deny preserved by original visibility bridge");rejects(403,()->CourseActivityIntegration.dispatch(denied,101,scope),"explicit deny blocks direct ID access");
        rejects(400,()->call(false,"GET","/api/course-activity/dispatches/101?scope_id="+scope+"&scope_id="+scope,null),"duplicate query rejected");rejects(405,()->call(false,"POST","/api/course-activity/scopes/"+scope,map()),"report mutation method rejected");
        Map<String,Object> old=Db.one("SELECT * FROM m04_course_activity_revisions WHERE dispatch_id=102 AND version=1");Db.exec("DELETE FROM m04_course_activity_revisions WHERE dispatch_id=102 AND version=1");rejects(409,()->report(),"missing audit revision fails closed");Db.exec("INSERT INTO m04_course_activity_revisions VALUES(?,?,?,?)",old.get("dispatch_id"),old.get("version"),old.get("payload"),old.get("digest"));
        Db.exec("UPDATE m04_course_activity_heads SET digest='bad' WHERE dispatch_id=101");rejects(409,()->report(),"tampered head fails closed");Db.exec("UPDATE m04_course_activity_heads SET digest=(SELECT digest FROM m04_course_activity_revisions WHERE dispatch_id=101 AND version=1) WHERE dispatch_id=101");
        check(Db.count("m04_teacher_bindings")==0&&Db.count("m05_financial_chains")==0,"no business identities, qualifications or financial entries invented");
        Auth.logout(token);rejects(401,()->report(),"revoked session fails closed");
        System.out.println("Stage2 course activity: "+checks+" checks passed. Synthetic H2: "+args[0]+"; listeners: none; network: none.");Db.get().close();
    }
    private static final class Exchange extends HttpExchange {
        private final String method; private final URI uri; private byte[] request=new byte[0]; final ByteArrayOutputStream response=new ByteArrayOutputStream(); int status;
        private final Headers requestHeaders = new Headers(), responseHeaders = new Headers();
        private final Map<String, Object> attributes = new HashMap<>();
        Exchange(String method, String target, String token, Map<String, Object> body) {
            this.method = method; this.uri = URI.create(target);
            if (token != null) requestHeaders.set("X-Token", token);
            requestHeaders.set("Content-Type", "application/json");
            if (body != null) {attributes.put("com.training.Api.body", copy(body));request=Json.write(body).getBytes(StandardCharsets.UTF_8);}
        }
        public Headers getRequestHeaders() { return requestHeaders; }
        public Headers getResponseHeaders() { return responseHeaders; }
        public URI getRequestURI() { return uri; }
        public String getRequestMethod() { return method; }
        public HttpContext getHttpContext() { return null; }
        public void close() {}
        public InputStream getRequestBody() { return new ByteArrayInputStream(request); }
        public OutputStream getResponseBody() { return response; }
        public void sendResponseHeaders(int code, long length) {status=code;}
        public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 1); }
        public int getResponseCode() { return 0; }
        public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 1); }
        public String getProtocol() { return "HTTP/1.1"; }
        public Object getAttribute(String name) { return attributes.get(name); }
        public void setAttribute(String name, Object value) { attributes.put(name, value); }
        public void setStreams(InputStream in, OutputStream out) {}
        public HttpPrincipal getPrincipal() { return null; }
    }
}
