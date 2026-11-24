package com.training;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static com.training.OrganizationAccess.*;
import static com.training.RecommendationDecision.map;

/** Isolated synthetic real Auth/M01/API regression; no listener, network, model, or production data. */
public final class LegacyOwnershipTest {
    static final String PASSWORD="SYNTHETIC-LEGACY-20261029-ONLY";
    static final Map<Integer,String> tokens=new HashMap<>();
    static final Map<Integer,Auth.Session> actors=new HashMap<>();
    static int checks,sequence;
    record Group(long demand,long bid,long project,long dispatch,long questionnaire,long fee,long charge,long cost,long eval) {}
    interface Work {void run()throws Exception;}
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void rejects(int status,Work work,String label)throws Exception{try{work.run();throw new AssertionError(label+" did not reject");}catch(Api.ApiException e){check(e.code==status,label+" expected="+status+" actual="+e.code+" "+e.getMessage());}}
    static long n(Object x){return ((Number)x).longValue();}
    @SuppressWarnings("unchecked") static Map<String,Object> obj(Object x){return (Map<String,Object>)x;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Object x){return (List<Map<String,Object>>)x;}
    static void login(int id)throws Exception{String token=Auth.login(id==1?"admin":"syn-legacy-"+id,PASSWORD);tokens.put(id,token);actors.put(id,Auth.get(token));check(actors.get(id)!=null,"synthetic login "+id);}
    static Auth.Session actor(int id){return actors.get(id);}
    static void fixture(Path path)throws Exception {
        if(!path.toAbsolutePath().toString().contains("yanxu-legacy-ownership")||!Files.isDirectory(path))throw new IllegalArgumentException("Caller-owned yanxu-legacy-ownership empty directory required");
        try(var files=Files.list(path)){if(files.findAny().isPresent())throw new IllegalArgumentException("Fixture directory must be empty");}
        System.setProperty("data.dir",path.toString());System.setProperty("bootstrap.demo","false");System.setProperty("bootstrap.admin.password",PASSWORD);System.setProperty("login.email.mode","legacy");Db.init();
        for(int i=2;i<=8;i++)Db.exec("INSERT INTO users(id,username,password,name,role,status) VALUES(?,?,?,?,?,1)",i,"syn-legacy-"+i,Auth.hash(PASSWORD),"合成账号"+i,i==4?"viewer":"manager");
        Set<String> roles=Set.of("WRITE","READ","DENY");RelationRule optional=new RelationRule(false,roles,false,false);List<RoleRelations> relations=roles.stream().map(r->new RoleRelations(r,optional,optional)).toList();List<Person> people=new ArrayList<>();List<AccountBinding> bindings=new ArrayList<>();
        for(int i=2;i<=8;i++){String org=i==3?"B":i==7?"OFF":i==8?"C":"A",role=i==4?"READ":i==5?"DENY":"WRITE";people.add(new Person("P"+i,org,Set.of(),null,null,Set.of(role),true));if(i!=6)bindings.add(new AccountBinding(i,"P"+i,true));}
        List<Grant> grants=new ArrayList<>();for(String role:roles){grants.add(new Grant("read-"+role,role,"demand.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()));if(!role.equals("READ"))grants.add(new Grant("write-"+role,role,"demand.write",Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));}
        grants.add(new Grant("deny-read","DENY","demand.read",Action.VIEW,Effect.DENY,Scope.OWN_ORG,Set.of()));grants.add(new Grant("deny-write","DENY","demand.write",Action.HANDLE,Effect.DENY,Scope.OWN_ORG,Set.of()));
        login(1);OrganizationAccessStore.publish(actor(1),null,new Configuration("SYN-LEGACY-1",new CodeRules("[A-Z]+","P[0-9]+","[A-Z]+"),roles,List.of(new Organization("A",null,true),new Organization("B",null,true),new Organization("C",null,true),new Organization("OFF",null,false)),people,relations,bindings,grants));for(int i=1;i<=8;i++)login(i);
        Db.exec("INSERT INTO teachers(id,name,status,fee_rate) VALUES(1,'同名教师','在库',100),(2,'同名教师','在库',200)");
    }
    static Group seed(String label,long teacher)throws Exception {
        long d=Db.insert("INSERT INTO demands(title,unit,hours,status) VALUES(?,?,2,'已立项')",label+"需求","同名单位");
        long b=Db.insert("INSERT INTO bids(demand_id,amount,status) VALUES(?,100,'已中标')",d);
        long p=Db.insert("INSERT INTO projects(demand_id,bid_id,title,unit,hours,amount,start_date,end_date,owner,delivery_mode,venue,contract_no,status) VALUES(?,?,?,'同名单位',2,100,'2020-01-01','2020-01-01','合成负责人','线下','合成会场','SYN-C','进行中')",d,b,label+"项目");
        long dispatch=Db.insert("INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,start_time,end_time,venue,hours,status,material_status) VALUES(?,?,'合成课程','2020-01-01','09:00','10:00','合成会场',2,'已发送','已就绪')",p,teacher);
        String questions=Json.write(List.of(map("type","score","title","合成评分")));
        long q=Db.insert("INSERT INTO questionnaires(title,target,project_id,questions,status) VALUES(?,'学员',?,?,'已发布')",label+"问卷",p,questions);
        long send=Db.insert("INSERT INTO q_sends(questionnaire_id,channel,target_desc,send_count,token) VALUES(?,'微信','合成对象',10,?)",q,label+"-public-token");
        Db.insert("INSERT INTO q_responses(send_id,questionnaire_id,respondent,answers,avg_score) VALUES(?,?,'合成回答者',?,4)",send,q,Json.write(List.of(map("value",4))));
        long fee=Db.insert("INSERT INTO fees(project_id,teacher_id,hours,rate,amount,status) VALUES(?,?,2,100,200,'待发放')",p,teacher);
        long charge=Db.insert("INSERT INTO charges(project_id,amount,received,status) VALUES(?,100,0,'未收费')",p);
        long cost=Db.insert("INSERT INTO costs(project_id,type,amount,note) VALUES(?,'交通',10,?)",p,label+"成本");
        long eval=Db.insert("INSERT INTO teacher_evals(project_id,teacher_id,score,comment) VALUES(?,?,?,?)",p,teacher,teacher==1?3:5,label+"评价");
        return new Group(d,b,p,dispatch,q,fee,charge,cost,eval);
    }
    static Map<String,Object> registration(String type,long id,String org)throws Exception {Map<String,Object> p=LegacyOwnershipIntegration.preview(actor(1),map("type",type,"id",id,"organization_code",org));return map("type",type,"id",id,"organization_code",org,"expected_version",0,"source_version",p.get("source_version"),"reason","合成原始来源已由管理员逐项核实","request_id","synthetic-register-"+(++sequence));}
    static void register(Group group,String org)throws Exception {LegacyOwnershipIntegration.register(actor(1),registration("PROJECT",group.project(),org));}
    static String state()throws Exception {List<Object> records=new ArrayList<>();for(String table:List.of("demands","bids","projects","dispatches","fees","charges","costs","questionnaires","q_sends","q_responses","legacy_ownership","legacy_ownership_requests","workflow_acceptances","dispatch_confirmation_occupancy","dispatch_confirmation_requests"))records.add(Db.query("SELECT * FROM "+table+" ORDER BY 1"));return Json.write(records);}
    static final class Exchange extends HttpExchange {
        final String method;final URI uri;final Headers request=new Headers(),response=new Headers();final Map<String,Object> attrs=new HashMap<>();final byte[] input;final ByteArrayOutputStream output=new ByteArrayOutputStream();int status;
        Exchange(int actor,String method,String path,Map<String,Object> body){this(actor,method,path,body==null?"":Json.write(body));}
        Exchange(int actor,String method,String path,String raw){this.method=method;uri=URI.create(path);request.set("Content-Type","application/json");if(actor>0)request.set("X-Token",tokens.get(actor));input=raw.getBytes(StandardCharsets.UTF_8);}
        public Headers getRequestHeaders(){return request;}public Headers getResponseHeaders(){return response;}public URI getRequestURI(){return uri;}public String getRequestMethod(){return method;}public HttpContext getHttpContext(){return null;}public void close(){}public InputStream getRequestBody(){return new ByteArrayInputStream(input);}public OutputStream getResponseBody(){return output;}public void sendResponseHeaders(int code,long size){status=code;}public InetSocketAddress getRemoteAddress(){return new InetSocketAddress("127.0.0.1",1);}public InetSocketAddress getLocalAddress(){return getRemoteAddress();}public int getResponseCode(){return status;}public String getProtocol(){return "HTTP/1.1";}public Object getAttribute(String name){return attrs.get(name);}public void setAttribute(String name,Object value){attrs.put(name,value);}public void setStreams(InputStream in,OutputStream out){}public HttpPrincipal getPrincipal(){return null;}
    }
    static Object http(int actor,String method,String path,Map<String,Object> body,int expected)throws Exception {Exchange ex=new Exchange(actor,method,path,body);Api.handle(ex);String raw=ex.output.toString(StandardCharsets.UTF_8);check(ex.status==expected,method+" "+path+" status "+expected+" actual "+ex.status+" "+raw);return obj(Json.parse(raw)).get("data");}
    static void noChangeHttp(int actor,String path,Map<String,Object> body,int status)throws Exception {String before=state();http(actor,"POST",path,body,status);check(before.equals(state()),"rejected mutation unchanged "+path);}
    static void boundary(Group a,Group b,Group unknown)throws Exception {
        for(String table:List.of("demands","bids","projects","dispatches","teacher_evals","questionnaires","charges","fees","costs","q_sends","q_responses")) {
            List<Map<String,Object>> all=Db.query("SELECT * FROM "+table);List<Map<String,Object>> allowed=WorkflowReadAccessBridge.listRows(table,all,actor(2));
            check(allowed.size()==2,table+" owner reads exactly two registered records");
            for(Map<String,Object> row:allowed)LegacyOwnershipIntegration.requireRow(table,n(row.get("id")),actor(2),false);
            check(WorkflowReadAccessBridge.listRows(table,all,actor(5)).isEmpty(),table+" explicit DENY hides historical data");check(WorkflowReadAccessBridge.listRows(table,all,actor(6)).isEmpty(),table+" no binding hides historical data");check(WorkflowReadAccessBridge.listRows(table,all,actor(7)).isEmpty(),table+" disabled organization hides historical data");check(WorkflowReadAccessBridge.listRows(table,all,actor(8)).isEmpty(),table+" third institution cannot see unrelated history");check(WorkflowReadAccessBridge.listRows(table,all,actor(4)).size()==2,table+" read-only owner retains read authority");
        }
        for(String module:List.of("demands","bids","projects","dispatches","teacher_evals","questionnaires","charges","fees","costs")) {
            String data=Json.write(http(2,"GET","/api/"+module,null,200));check(!data.contains("B-SECRET")&&!data.contains("U-SECRET"),module+" HTTP list omits other/unregistered rows");
        }
        http(3,"GET","/api/projects/check?id="+a.project(),null,403);http(3,"GET","/api/stats/q?id="+a.questionnaire(),null,403);http(1,"GET","/api/stats/q?id="+unknown.questionnaire(),null,200);
        for(String module:List.of("demands","bids","projects","dispatches","teacher_evals","questionnaires","charges","fees","costs")) {
            long id=switch(module){case "demands"->a.demand();case "bids"->a.bid();case "projects"->a.project();case "dispatches"->a.dispatch();case "teacher_evals"->a.eval();case "questionnaires"->a.questionnaire();case "charges"->a.charge();case "fees"->a.fee();default->a.cost();};
            noChangeHttp(3,"/api/"+module,map("id",id),403);noChangeHttp(3,"/api/"+module+"/delete",map("id",id),403);
        }
        for(String route:List.of("projects/start","projects/complete","projects/archive"))noChangeHttp(3,"/api/"+route,map("id",a.project()),403);
        for(String route:List.of("dispatches/send","dispatches/confirm","dispatches/complete"))noChangeHttp(3,"/api/"+route,map("id",a.dispatch(),"accept",0,"reason","合成拒绝"),403);
        noChangeHttp(3,"/api/bids/win",map("id",a.bid()),403);noChangeHttp(3,"/api/fees/calc",map("project_id",a.project()),403);noChangeHttp(3,"/api/fees/pay",map("id",a.fee()),403);noChangeHttp(3,"/api/charges/receive",map("id",a.charge()),403);
        for(String route:List.of("q/publish","q/close","q/send"))noChangeHttp(3,"/api/"+route,map("id",a.questionnaire()),403);
        noChangeHttp(2,"/api/costs",map("id",a.cost(),"project_id",b.project(),"amount",10,"type","交通"),403);
        noChangeHttp(1,"/api/costs",map("id",unknown.cost(),"project_id",a.project(),"amount",10,"type","交通"),403);
        noChangeHttp(2,"/api/demands/delete",map("id",a.demand()),409);noChangeHttp(4,"/api/costs",map("id",a.cost(),"project_id",a.project(),"amount",10),403);
        Map<String,Object> update=Db.one("SELECT * FROM costs WHERE id=?",a.cost());update.put("note","A permitted edit");http(2,"POST","/api/costs",update,200);
        for(String token:List.of("A-SECRET-public-token","U-SECRET-public-token")){http(0,"GET","/api/q/pub?token="+token,null,409);noChangeHttp(0,"/api/q/answer",map("token",token,"answers",List.of(map("value",4))),409);}
        noChangeHttp(2,"/api/q/send",map("id",a.questionnaire(),"send_count",1,"target_desc","合成对象"),409);
        String report=String.valueOf(http(2,"GET","/api/stats/report",null,200));check(!report.contains("B-SECRET")&&!report.contains("U-SECRET"),"report cannot leak hidden organizations");
    }
    static void statistics(Group a,Group c)throws Exception {
        Db.exec("UPDATE dispatches SET status='已完成' WHERE id IN (?,?)",a.dispatch(),c.dispatch());
        Map<String,Object> data=obj(http(2,"GET","/api/stats/overview",null,200));List<Map<String,Object>> scores=rows(data.get("teacher_score"));check(scores.size()==2,"same-name evaluation teachers stay two rows");check(scores.stream().map(r->r.get("teacher_id")).distinct().count()==2,"score uses stable teacher identities");check(scores.stream().allMatch(r->n(r.get("cnt"))==1),"same-name scores not merged");List<Map<String,Object>> workload=rows(data.get("by_teacher"));check(workload.size()==2&&workload.stream().allMatch(r->r.get("display_name").toString().contains("#")),"workload includes distinct display identities");
        String report=(String)http(2,"GET","/api/stats/report",null,200);check(report.contains("同名教师 # 1")&&report.contains("同名教师 # 2"),"text report has two stable identities");
        Db.exec("UPDATE teachers SET name='改名教师' WHERE id=1");List<Map<String,Object>> after=rows(obj(http(2,"GET","/api/stats/overview",null,200)).get("teacher_score"));check(after.size()==2,"rename does not split identity");
    }
    static void confirmation(Group a,Group b,Group c,Group unknown)throws Exception {
        rejects(409,()->DispatchConfirmationGuard.preview(actor(1),unknown.dispatch()),"unregistered cannot confirm even global admin");rejects(403,()->DispatchConfirmationGuard.preview(actor(3),a.dispatch()),"foreign confirmation forbidden");
        Map<String,Object> preview=DispatchConfirmationGuard.preview(actor(2),a.dispatch());check(Boolean.TRUE.equals(preview.get("can_confirm")),"registered historical project can confirm");Map<String,Object> request=map("id",a.dispatch(),"accept",1,"expected_version",preview.get("expected_version"),"request_id","legacy-confirm-A-001");DispatchConfirmationGuard.confirm(actor(2),request);String before=state();DispatchConfirmationGuard.confirm(actor(2),request);check(before.equals(state()),"confirmation replay no duplicate mutation");
        check(Boolean.FALSE.equals(DispatchConfirmationGuard.preview(actor(3),b.dispatch()).get("can_confirm")),"global same-teacher conflict crosses institutions");check(Boolean.TRUE.equals(DispatchConfirmationGuard.preview(actor(2),c.dispatch()).get("can_confirm")),"same name different teacher_id has no conflict");
        http(2,"POST","/api/dispatches/complete",map("id",a.dispatch()),200);check("已完成".equals(Db.one("SELECT status FROM dispatches WHERE id=?",a.dispatch()).get("status")),"registered historical confirmed dispatch can complete");
        http(2,"POST","/api/projects/complete",map("id",a.project()),200);check("已完成".equals(Db.one("SELECT status FROM projects WHERE id=?",a.project()).get("status")),"legacy project can complete through original gates");
        http(2,"POST","/api/fees/pay",map("id",a.fee()),200);http(2,"POST","/api/charges/receive",map("id",a.charge(),"amount",100),200);http(2,"POST","/api/q/close",map("id",a.questionnaire()),200);http(2,"POST","/api/projects/archive",map("id",a.project()),200);check("已归档".equals(Db.one("SELECT status FROM projects WHERE id=?",a.project()).get("status")),"legacy project can archive after original financial gates");
    }
    static void derived()throws Exception {
        long demand=Db.insert("INSERT INTO demands(title,unit,hours,status) VALUES('DERIVED','同名单位',2,'已投标')");long bid=Db.insert("INSERT INTO bids(demand_id,amount,status) VALUES(?,100,'待评审')",demand);LegacyOwnershipIntegration.register(actor(1),registration("DEMAND",demand,"A"));Map<String,Object> result=obj(http(2,"POST","/api/bids/win",map("id",bid),200));long project=n(result.get("project_id"));check("A".equals(LegacyOwnershipIntegration.source("PROJECT",project).get("organization_code")),"bids/win derives registered stable demand ownership");check(Db.count("workflow_acceptances")==0,"registration and derived project never fabricate acceptances");check(!DeliverySettlementIntegration.controlled(project),"registration does not opt into controlled settlement");
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("One caller-owned empty fixture directory required");fixture(Path.of(args[0]));
        try {
            Group a=seed("A-SECRET",1),b=seed("B-SECRET",1),c=seed("A-SECOND",2),unknown=seed("U-SECRET",1);
            for(String rawId:List.of("1.0000000000000001","1.00000000000000001","1e0","9007199254740992")){Exchange invalid=new Exchange(1,"POST","/api/legacy-ownership/preview","{\"type\":\"PROJECT\",\"id\":"+rawId+",\"organization_code\":\"A\"}");Api.handle(invalid);check(invalid.status==400,"raw registration ID precision boundary "+rawId);}
            check(WorkflowReadAccessBridge.listRows("projects",Db.query("SELECT * FROM projects"),actor(2)).isEmpty(),"all unregistered data hidden before registration");check(WorkflowReadAccessBridge.listRows("projects",Db.query("SELECT * FROM projects"),actor(1)).size()==4,"global admin retains historical access");
            rejects(403,()->LegacyOwnershipIntegration.preview(actor(2),map("type","PROJECT","id",a.project(),"organization_code","A")),"business manager cannot register");rejects(409,()->LegacyOwnershipIntegration.preview(actor(1),map("type","PROJECT","id",a.project(),"organization_code","OFF")),"disabled organization cannot register");
            Map<String,Object> stale=registration("PROJECT",a.project(),"A");Db.exec("UPDATE projects SET title='A-SECRET-changed' WHERE id=?",a.project());rejects(409,()->LegacyOwnershipIntegration.register(actor(1),stale),"stale source preview rejected");check(Db.count("legacy_ownership")==0,"stale registration leaves no rows");
            Map<String,Object> first=registration("PROJECT",a.project(),"A");for(String rawVersion:List.of("0.00000000000000000000000000000000001","0e0")){String raw=Json.write(first).replace("\"expected_version\":0","\"expected_version\":"+rawVersion);String unchanged=state();Exchange invalid=new Exchange(1,"POST","/api/legacy-ownership/register",raw);Api.handle(invalid);check(invalid.status==400,"raw registration version precision boundary "+rawVersion);check(unchanged.equals(state()),"invalid raw numeric registration has no side effect");}
            http(1,"POST","/api/legacy-ownership/register",first,200);String before=state();LegacyOwnershipIntegration.register(actor(1),first);check(before.equals(state()),"registration replay is idempotent");Map<String,Object> changed=new LinkedHashMap<>(first);changed.put("reason","different synthetic reason");rejects(409,()->LegacyOwnershipIntegration.register(actor(1),changed),"request replay content conflict");
            register(b,"B");register(c,"A");check(LegacyOwnershipIntegration.contexts(actor(2),"A").size()==4,"registered history contexts use existing org read permission");check(LegacyOwnershipIntegration.contexts(actor(3),"A").isEmpty(),"foreign V2 contexts absent");
            boundary(a,b,unknown);confirmation(a,b,c,unknown);statistics(a,c);derived();
            long originalDemand=a.demand();Db.exec("UPDATE projects SET demand_id=? WHERE id=?",b.demand(),a.project());rejects(409,()->LegacyOwnershipIntegration.source("PROJECT",a.project()),"source relink cannot escape registration");check(WorkflowIntegration.visibleRows("projects",Db.query("SELECT * FROM projects WHERE id=?",a.project()),actor(2)).isEmpty(),"corrupt registered source hidden");Db.exec("UPDATE projects SET demand_id=? WHERE id=?",originalDemand,a.project());
            long orphan=Db.insert("INSERT INTO costs(project_id,type,amount) VALUES(999999,'孤儿',1)");check(WorkflowIntegration.visibleRows("costs",Db.query("SELECT * FROM costs WHERE id=?",orphan),actor(2)).isEmpty(),"orphan hidden from institution");check(WorkflowIntegration.visibleRows("costs",Db.query("SELECT * FROM costs WHERE id=?",orphan),actor(1)).size()==1,"orphan remains global admin only");
            Auth.revokeUserSessions(actor(2).uid);rejects(401,()->LegacyOwnershipIntegration.contexts(actor(2),"A"),"revoked session cannot read contexts");
            System.out.println("LegacyOwnershipTest: "+checks+" synthetic checks passed; real API in-process, no listener or external call.");
        } finally {Db.exec("SHUTDOWN");}
    }
}
