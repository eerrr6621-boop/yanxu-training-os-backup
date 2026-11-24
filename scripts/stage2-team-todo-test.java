package com.training;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static com.training.OrganizationAccess.*;

/** Caller-owned, fresh synthetic H2 only. No HTTP listener, mail, or production configuration. */
final class Stage2TeamTodoTest {
    static int checks; static Auth.Session admin,filler,leader,bp,first,second,cross,outsider;
    interface Work { void run()throws Exception; }
    static void check(boolean ok,String name){checks++;if(!ok)throw new AssertionError(name);}
    static void reject(int code,Work work,String name)throws Exception{try{work.run();throw new AssertionError("Expected rejection: "+name);}catch(Api.ApiException error){check(error.code==code,name+" status="+error.code+" "+error.getMessage());}}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object value){return (Map<String,Object>)value;}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    static long n(Map<String,Object> m,String key){return ((Number)m.get(key)).longValue();}
    static Map<String,Object> call(String op,Auth.Session who,Map<String,Object> body)throws Exception{return IntegrationWorkflowTest.call(op,who,body);}
    static Map<String,Object> fresh(String key,String path)throws Exception{return call("draft",filler,IntegrationWorkflowTest.full(key,path));}
    static Map<String,Object> submit(Map<String,Object>d,String key)throws Exception{return call("submit",filler,IntegrationWorkflowTest.command(d,key));}
    static Map<String,Object> ready(String key)throws Exception{
        Map<String,Object>d=submit(fresh(key,"direct"),key+"-submit");
        d=call("approval",leader,IntegrationWorkflowTest.approve(d,"APPROVE",key+"-leader"));
        return call("approval",bp,IntegrationWorkflowTest.approve(d,"APPROVE",key+"-bp"));
    }
    static Map<String,Object> command(Map<String,Object>d,String key){Map<String,Object>b=IntegrationWorkflowTest.command(d,key);b.put("expected_todo_version",map(d.get("team_todo")).get("version"));return b;}
    static Map<String,Object> claim(Map<String,Object>d,String key,String owner){Map<String,Object>b=command(d,key);b.put("owner_code",owner);b.put("note","合成认领/改派说明");return b;}
    static Map<String,Object> detail(long id)throws Exception{return WorkflowIntegration.detail(id,first);}
    static Map<String,Object> item(long id,Auth.Session who)throws Exception{return rows(TeamTodoIntegration.list(who).get("items")).stream().filter(x->n(x,"id")==id).findFirst().orElse(null);}
    static Configuration config(){
        Configuration base=IntegrationWorkflowTest.config();
        List<Person> people=new ArrayList<>(base.people());
        people.add(new Person("P7","900",Set.of("001"),null,null,Set.of("TEAM"),true));
        people.add(new Person("P8","999",Set.of("001"),null,null,Set.of("TEAM"),true));
        people.add(new Person("P9","900",Set.of("001"),null,null,Set.of("OUT"),true));
        people.add(new Person("P10","900",Set.of("001"),null,null,Set.of("TEAM"),true));
        people.add(new Person("P11","900",Set.of("001"),null,null,Set.of("TEAM"),true));
        people.add(new Person("P12","900",Set.of("001"),null,null,Set.of("TEAM","BLOCK"),true));
        List<AccountBinding> bindings=new ArrayList<>(base.accountBindings());for(int i:new int[]{7,8,9,10,12})bindings.add(new AccountBinding(i,"P"+i,true));
        List<Grant> grants=new ArrayList<>(base.grants());grants.add(new Grant("deny-team","BLOCK","demand.accept",Action.HANDLE,Effect.DENY,Scope.NAMED_ORGS,Set.of("001")));
        List<RoleRelations> relations=new ArrayList<>(base.relations());RelationRule optional=new RelationRule(false,Set.of("LEADER","BP"),false,false);relations.add(new RoleRelations("BLOCK",optional,optional));
        Set<String> roles=new HashSet<>(base.roleCodes());roles.add("BLOCK");
        return new Configuration("synthetic-team-todo-v1",base.codeRules(),roles,base.organizations(),people,relations,bindings,grants);
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Fresh empty synthetic data directory required");
        Path data=Path.of(args[0]).toRealPath();try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Data must be empty");}
        System.setProperty("data.dir",data.toString());System.setProperty("login.email.mode","legacy");
        Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64),password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
        FirstBindStore.init();TrustedDevices.init();
        Db.exec("CREATE TABLE demands(id IDENTITY PRIMARY KEY,title VARCHAR(200),unit VARCHAR(200),contact VARCHAR(64),phone VARCHAR(32),hours DOUBLE,content CLOB,teacher_req CLOB,expect_date VARCHAR(32),status VARCHAR(16),remark CLOB,training_province VARCHAR(64),training_city VARCHAR(64),training_mode VARCHAR(500),training_period VARCHAR(500))");
        Db.exec("CREATE TABLE projects(id IDENTITY PRIMARY KEY,demand_id BIGINT,bid_id BIGINT,title VARCHAR(200),unit VARCHAR(200),hours DOUBLE,amount DOUBLE,start_date VARCHAR(32),end_date VARCHAR(32),owner VARCHAR(64),participant_count INT,delivery_mode VARCHAR(500),status VARCHAR(16),remark CLOB)");
        String password="synthetic-team-todo-only",hash=Auth.hash(password);for(int i=1;i<=12;i++)Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)",i,"synthetic-"+i,hash,"合成人员 "+i,i==1?"admin":"viewer");
        OrganizationAccessStore.init();WorkflowIntegration.init();WorkflowIntegration.init();NotificationChannelsIntegration.init();
        admin=Auth.get(Auth.login("synthetic-1",password));OrganizationAccessStore.publish(admin,null,config());
        filler=Auth.get(Auth.login("synthetic-2",password));leader=Auth.get(Auth.login("synthetic-3",password));bp=Auth.get(Auth.login("synthetic-4",password));first=Auth.get(Auth.login("synthetic-5",password));second=Auth.get(Auth.login("synthetic-7",password));cross=Auth.get(Auth.login("synthetic-8",password));outsider=Auth.get(Auth.login("synthetic-6",password));
        Db.exec("UPDATE users SET status=0 WHERE id=10");
        Map<String,Object> draft=fresh("draft","direct"),pending=submit(fresh("pending","direct"),"pending-submit");
        check(item(n(draft,"id"),first)==null,"draft excluded");check(item(n(pending,"id"),first)==null,"unapproved excluded");
        reject(409,()->TeamTodoIntegration.mutate("claim",first,claim(pending,"early","P5")),"cannot claim before approvals");
        Map<String,Object> d=ready("ready");long id=n(d,"id");Map<String,Object> initial=item(id,first);
        check(initial.get("status").equals("unclaimed"),"approved enters unclaimed queue");
        check(map(initial.get("actions")).get("accept").equals(true),"original acceptance still available without claim");
        Set<String> candidates=new HashSet<>();for(Map<String,Object> person:rows(initial.get("members")))candidates.add(String.valueOf(person.get("code")));
        check(candidates.equals(Set.of("P5","P7")),"only active bound same-team authorized members; deny respected");
        check(rows(TeamTodoIntegration.list(outsider).get("items")).isEmpty(),"out-of-scope list reveals no items");
        reject(403,()->TeamTodoIntegration.mutate("claim",outsider,claim(d,"out","P6")),"outsider mutation denied");
        for(String invalid:List.of("P8","P9","P10","P11","P12","GUESSED"))reject(403,()->TeamTodoIntegration.mutate("claim",first,claim(d,"invalid-"+invalid,invalid)),"invalid target "+invalid);
        Map<String,Object> spoof=claim(d,"spoof","P5");spoof.put("status","accepted");reject(422,()->TeamTodoIntegration.mutate("claim",first,spoof),"client cannot fabricate completed state");
        Map<String,Object> firstClaim=claim(d,"claim-one","P5");long notices=Db.count("workflow_outbox");
        TeamTodoIntegration.mutate("claim",first,firstClaim);Map<String,Object> followed=detail(id);
        check(map(followed.get("team_todo")).get("status").equals("following"),"claim gives following status");
        check(n(followed,"version")==n(d,"version")+1,"claim advances demand version");
        check(Db.count("workflow_acceptances")==0&&Db.count("projects")==0,"claim does not accept or create project");
        check(Db.count("workflow_outbox")==notices,"claim does not dispatch new notification types");
        TeamTodoIntegration.mutate("claim",first,firstClaim);check(Db.count("workflow_team_todo_events")==1,"same request replay exactly once");
        Map<String,Object> changed=new LinkedHashMap<>(firstClaim);changed.put("note","changed");reject(409,()->TeamTodoIntegration.mutate("claim",first,changed),"request payload collision rejected");
        reject(409,()->TeamTodoIntegration.mutate("claim",second,claim(d,"stale","P7")),"stale claim rejected");
        check(item(id,cross)==null,"other-team claimed item excluded");
        reject(403,()->TeamTodoIntegration.mutate("claim",cross,claim(followed,"cross-team","P8")),"other team cannot steal claim");
        Map<String,Object> crossAccept=IntegrationWorkflowTest.command(followed,"cross-accept");crossAccept.put("team_code","999");reject(409,()->call("accept",cross,crossAccept),"other team cannot accept assigned item");
        Map<String,Object> note=command(followed,"note-one");note.put("note","已联系，待确认档期");
        reject(403,()->TeamTodoIntegration.mutate("follow-up",second,note),"nonowner cannot edit follow-up");
        TeamTodoIntegration.mutate("follow-up",first,note);TeamTodoIntegration.mutate("follow-up",first,note);
        check(rows(item(id,first).get("history")).size()==2,"follow-up durable and replay idempotent");
        check(!map(WorkflowIntegration.detail(id,filler).get("team_todo")).containsKey("last_note"),"general demand reader cannot read internal follow-up notes");
        Map<String,Object> beforeAssign=detail(id),assign=claim(beforeAssign,"reassign","P7");
        assign.put("note","");reject(422,()->TeamTodoIntegration.mutate("claim",first,assign),"reassign needs reason");assign.put("note","由同组继续协调");
        TeamTodoIntegration.mutate("claim",first,assign);Map<String,Object> reassigned=detail(id);
        check(!Boolean.TRUE.equals(map(reassigned.get("actions")).get("accept")),"previous owner sees refreshed acceptance availability");
        check(map(WorkflowIntegration.detail(id,second).get("actions")).get("accept").equals(true),"new owner can use original acceptance");
        Db.exec("UPDATE users SET status=0 WHERE id=7");check(item(id,first).get("owner_available").equals(false),"disabled owner explicitly needs reassignment");
        check(item(id,first).get("owner_label").equals("P7"),"disabled owner display falls back to code without directory expansion");
        TeamTodoIntegration.mutate("claim",first,claim(reassigned,"recover-owner","P5"));
        Map<String,Object> recovered=detail(id),accept=IntegrationWorkflowTest.command(recovered,"accept-final");accept.put("team_code","900");
        call("accept",first,accept);call("accept",first,accept);
        Map<String,Object> done=item(id,first);check(done.get("status").equals("accepted")&&n(done,"project_id")>0,"real acceptance automatically completes follow-up");
        check(Db.count("projects")==1&&Db.count("workflow_acceptances")==1,"accept retry makes one project");
        check(rows(done.get("history")).size()==4&&done.get("owner_code").equals("P5"),"completed history and owner retained");
        check(done.get("owner_label").equals("合成人员 5")&&done.get("accepted_by_label").equals("合成人员 5")&&rows(done.get("members")).isEmpty(),"accepted labels reuse authorized current bound account without exposing candidates");
        check(!map(WorkflowIntegration.detail(id,filler).get("team_todo")).containsKey("owner_label"),"current team display names not added to ordinary demand projection");
        Db.exec("UPDATE users SET name='' WHERE id=5");check(item(id,first).get("owner_label").equals("P5")&&item(id,first).get("accepted_by_label").equals("P5"),"blank current display name falls back to stable code");Db.exec("UPDATE users SET name='合成人员 5' WHERE id=5");
        check(!Boolean.TRUE.equals(map(done.get("actions")).get("claim")),"completed item no longer editable");
        reject(409,()->TeamTodoIntegration.mutate("follow-up",first,command(detail(id),"late-note")),"cannot follow up after acceptance");
        check(n(map(TeamTodoIntegration.list(first).get("counts")),"mine")==0,"accepted items excluded from active my count");
        Db.get().close();check(rows(item(id,first).get("history")).size()==4,"follow-up survives reconnect");
        Map<String,Object> bid=submit(fresh("bid","bid"),"bid-submit");check(item(n(bid,"id"),first)==null,"pending bid excluded");
        Map<String,Object> win=IntegrationWorkflowTest.command(bid,"bid-win");win.putAll(Map.of("result","won","result_date","2026-10-26","external_approval_ref","SYNTHETIC-OFFICE-001"));bid=call("bid-result",filler,win);
        check(item(n(bid,"id"),first).get("ready_reason").equals("已中标"),"winning bid enters queue");
        Map<String,Object> lost=submit(fresh("lost","bid"),"lost-submit"),loss=IntegrationWorkflowTest.command(lost,"lost-result");loss.putAll(Map.of("result","lost","result_date","2026-10-26","external_approval_ref","SYNTHETIC-OFFICE-001"));lost=call("bid-result",filler,loss);check(item(n(lost,"id"),first)==null,"lost bid excluded");
        Db.exec("UPDATE users SET status=1 WHERE id=7");second=Auth.get(Auth.login("synthetic-7",password));
        Map<String,Object> race=ready("race");ExecutorService workers=Executors.newFixedThreadPool(2);CountDownLatch gate=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
        for(int i=0;i<2;i++){final int index=i;futures.add(workers.submit(()->{gate.await();try{TeamTodoIntegration.mutate("claim",index==0?first:second,claim(race,"race-"+index,index==0?"P5":"P7"));return true;}catch(Api.ApiException error){if(error.code!=409)throw error;return false;}}));}
        gate.countDown();int winners=0;for(Future<Boolean> result:futures)if(result.get())winners++;workers.shutdown();check(winners==1,"concurrent claim one winner");
        Map<String,Object> raceDone=detail(n(race,"id"));check(n(map(raceDone.get("team_todo")),"version")==1,"concurrent claim no overwrite");
        // Current directory changes can pause a combined approval after acceptance; keep factual history.
        Db.exec("UPDATE users SET status=1 WHERE id=10");Configuration current=config();List<Person> combinedPeople=new ArrayList<>();
        for(Person p:current.people())combinedPeople.add(p.personCode().equals("P2")?new Person("P2","001",Set.of(),"P3","P3",Set.of("FILLER"),true):p.personCode().equals("P3")?new Person("P3","002",Set.of("001"),null,null,Set.of("LEADER","BP"),true,Map.of("LEADER",Set.of("001"),"BP",Set.of("001"))):p);
        Configuration combined=new Configuration("synthetic-team-combined-v2",current.codeRules(),current.roleCodes(),current.organizations(),combinedPeople,current.relations(),current.accountBindings(),current.grants(),List.of(new CombinedApprovalAssignment("001","P3","LEADER","BP","SYNTHETIC-TEAM-COMBINED")));
        OrganizationAccessStore.publish(admin,current.version(),combined);
        filler=Auth.get(Auth.login("synthetic-2",password));leader=Auth.get(Auth.login("synthetic-3",password));first=Auth.get(Auth.login("synthetic-5",password));
        Map<String,Object> combinedDemand=submit(fresh("combined","direct"),"combined-submit");combinedDemand=call("approval",leader,IntegrationWorkflowTest.approve(combinedDemand,"APPROVE_COMBINED","combined-approve"));
        Map<String,Object> combinedAccept=IntegrationWorkflowTest.command(combinedDemand,"combined-accept");combinedAccept.put("team_code","900");combinedDemand=call("accept",first,combinedAccept);long combinedId=n(combinedDemand,"id");
        Db.exec("UPDATE users SET status=0 WHERE id=3");
        check(WorkflowIntegration.detail(combinedId,first).get("queue").equals("inconsistent"),"changed current approval directory no longer ready");
        check(item(combinedId,first).get("status").equals("accepted"),"real accepted history remains visible after directory pause");
        Auth.Session forged=new Auth.Session();forged.uid=5;reject(401,()->TeamTodoIntegration.list(forged),"forged session denied");
        Db.exec("UPDATE users SET status=0 WHERE id=5");reject(401,()->TeamTodoIntegration.mutate("claim",first,claim(raceDone,"revoked","P5")),"deactivated session cannot mutate");
        Db.exec("SHUTDOWN");System.out.println("Stage2TeamTodo: "+checks+" checks passed; isolated synthetic H2; no listener or external delivery");
    }
}
