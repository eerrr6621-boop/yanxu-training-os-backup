package com.training;
import java.nio.file.*;
import java.util.*;
import static com.training.OrganizationAccess.*;
import static com.training.IntegrationCombinedWorkflowTest.*;
import static com.training.ApprovalTemplateDefinition.*;

/** Full M03 store/auth/acceptance/outbox on one new synthetic DB, no server. */
public final class NextApprovalTemplateDemandTest {
    static Auth.Session extraBefore,extraAfter;static long seq;static int checks,groups;
    interface Job{void run()throws Exception;}
    static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void eq(Object a,Object b){ok(Objects.equals(a,b),"expected "+a+" got "+b);}
    static void reject(int code,Job action)throws Exception{checks++;try{action.run();throw new AssertionError("expected "+code);}catch(Api.ApiException e){if(e.code!=code)throw new AssertionError("expected "+code+" got "+e.code+" "+e.getMessage());}}
    static void group(String name,Job action)throws Exception{action.run();groups++;System.out.println("PASS "+name);}
    static Map<String,String> tables()throws Exception{Map<String,String> all=new TreeMap<>();for(var t:Db.query("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE'")){String name=t.get("table_name").toString();if(!name.matches("[A-Z_0-9]+"))throw new AssertionError();all.put(name,TrainingSummariesWorkflow.canonical(Db.query("SELECT * FROM \""+name+"\"").stream().map(TrainingSummariesWorkflow::canonical).sorted().toList()));}return all;}
    static void unchanged(Job action)throws Exception{var before=tables();action.run();eq(before,tables());}
    static void config(boolean combined,String revoked,boolean swap)throws Exception{
        var base=fixture(combined);List<Person>people=new ArrayList<>(base.people());people.add(new Person("P6","002",Set.of("001"),null,null,Set.of("LEADER"),true));people.add(new Person("P7","002",Set.of("001"),null,null,Set.of("LEADER"),true));List<AccountBinding>bindings=new ArrayList<>(base.accountBindings());bindings.add(new AccountBinding(6,swap?"P7":"P6",true));bindings.add(new AccountBinding(7,swap?"P6":"P7",true));
        var grants=base.grants().stream().filter(g->!g.resource().equals(revoked)).toList();publish(new Configuration(base.version(),base.codeRules(),base.roleCodes(),base.organizations(),people,base.relations(),bindings,grants,base.combinedApprovals()));extraBefore=login(6);extraAfter=login(7);
    }
    static Map<String,Object> template(boolean extras)throws Exception{
        var definition=NextApprovalTemplateTest.definition("BRANCH_THEN_BP",extras?"P6":null,extras?"P7":null);var old=ApprovalTemplatesIntegration.read(admin,Kind.M03_DEMAND,"001");var d=ApprovalTemplatesIntegration.command(admin,Kind.M03_DEMAND,"draft",Map.of("organization_code","001","expected_revision",old.get("head_revision"),"request_id","m03-template-draft-"+(++seq),"definition",definition,"reason","合成审批模板验证"));var p=ApprovalTemplatesIntegration.command(admin,Kind.M03_DEMAND,"preview",Map.of("organization_code","001","expected_revision",d.get("current_revision")));eq("FUTURE_M03_INSTANCES",p.get("applies_to"));return ApprovalTemplatesIntegration.command(admin,Kind.M03_DEMAND,"publish",Map.of("review_token",p.get("review_token"),"request_id","m03-template-publish-"+(++seq)));
    }
    static Map<String,Object> actionBody(Map<String,Object>d,String action,String request){Map<String,Object>b=action(d,action,request),a=map(d.get("approval"));if(Set.of("APPROVE","APPROVE_COMBINED","RETURN").contains(action))b.put("nodeId",map(a.get("approvalTemplate")).get("current_node_id"));return b;}
    static Map<String,Object> act(Map<String,Object>d,Auth.Session who,String action)throws Exception{return call("approval",who,actionBody(d,action,"m03-node-"+(++seq)));}
    static Map<String,Object> acceptBody(Map<String,Object>d){Map<String,Object>b=cmd(d,"m03-accept-"+(++seq));b.put("team_code","900");return b;}
    static Map<String,Object> fresh(String label)throws Exception{return submitted("m03-template-"+label+"-"+(++seq));}
    static Map<String,Object> current(Map<String,Object>d)throws Exception{return WorkflowIntegration.detail(n(d,"id"),filler);}
    static String node(Map<String,Object>d){return Objects.toString(map(map(d.get("approval")).get("approvalTemplate")).get("current_node_id"),"");}
    public static void main(String[]args)throws Exception{
        Path data=Path.of(args[0]).toAbsolutePath().normalize();if(!data.toString().contains("approval-template"))throw new IllegalArgumentException();try(var entries=Files.list(data)){if(entries.findAny().isPresent())throw new IllegalArgumentException("new synthetic DB only");}System.setProperty("data.dir",data.toString());System.setProperty("bootstrap.demo","false");System.setProperty("bootstrap.admin.password",PASS);Db.init();
        try{for(int i=2;i<=7;i++)Db.exec("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,'viewer',1)","synthetic-combined-"+i,Auth.hash(PASS),"SYNTHETIC "+i);loginAll();config(false,"",false);template(true);
            group("M03 true extra nodes block acceptance and produce accurate notices",()->{
                Map<String,Object>d=fresh("normal");eq("extra-before",node(d));eq("EXTRA_PENDING",map(d.get("approval")).get("status"));Map<String,Object> submitted=d;
                unchanged(()->reject(409,()->call("accept",team,acceptBody(submitted))));unchanged(()->reject(403,()->call("approval",reviewer,actionBody(submitted,"APPROVE","wrong-extra-actor"))));
                var inbox=NotificationChannelsIntegration.list(extraBefore,0,20);ok(((List<?>)inbox.get("items")).stream().map(IntegrationCombinedWorkflowTest::map).anyMatch(v->Boolean.TRUE.equals(v.get("actionable"))),"extra recipient actionable");
                d=act(d,extraBefore,"APPROVE");eq("required-branch",node(d));d=act(d,reviewer,"APPROVE");d=act(d,bp,"APPROVE");eq("extra-after",node(d));eq("EXTRA_PENDING",map(d.get("approval")).get("status"));eq("waiting_approval",d.get("queue"));Map<String,Object> partial=d;unchanged(()->reject(409,()->call("accept",team,acceptBody(partial))));
                eq(0L,((Number)Db.one("SELECT COUNT(*) AS n FROM workflow_outbox WHERE payload LIKE ?","%TEAM_READY%").get("n")).longValue());d=act(d,extraAfter,"APPROVE");eq("ready",d.get("queue"));eq(1L,((Number)Db.one("SELECT COUNT(*) AS n FROM workflow_outbox WHERE payload LIKE ?","%TEAM_READY%").get("n")).longValue());var request=acceptBody(d);d=call("accept",team,request);eq("accepted",d.get("queue"));config(false,"demand.accept",false);unchanged(()->reject(403,()->call("accept",team,request)));config(false,"",false);
            });
            group("M03 returns withdraw revise and resubmit keep entire frozen instance",()->{
                Map<String,Object>d=fresh("rounds");String version=Objects.toString(map(map(d.get("approval")).get("approvalTemplate")).get("template_version"));d=act(d,extraBefore,"APPROVE");d=act(d,reviewer,"APPROVE");d=act(d,bp,"APPROVE");d=act(d,extraAfter,"RETURN");eq("RETURNED",map(d.get("approval")).get("status"));
                ok(((List<?>)NotificationChannelsIntegration.list(extraAfter,0,50).get("items")).stream().map(IntegrationCombinedWorkflowTest::map).noneMatch(v->Boolean.TRUE.equals(v.get("actionable"))),"returned previous node has no ghost actionable task");template(false);d=act(d,filler,"RESUBMIT");eq("extra-before",node(d));eq(version,map(map(d.get("approval")).get("approvalTemplate")).get("template_version"));
                d=act(d,filler,"WITHDRAW");eq("WITHDRAWN",map(d.get("approval")).get("status"));d=act(d,filler,"RESUBMIT");d=act(d,extraBefore,"APPROVE");var edit=cmd(d,"m03-revise-"+(++seq));edit.put("title","合成实质改稿");edit.put("change_comment","本轮真实改稿重新审批");d=call("draft",filler,edit);eq("extra-before",node(d));eq(2L,n(d,"data_revision"));eq(version,map(map(d.get("approval")).get("approvalTemplate")).get("template_version"));
                Map<String,Object> newer=fresh("future");eq("required-branch",node(newer));
                config(false,"demand.write",false);unchanged(()->reject(403,()->call("draft",filler,edit)));config(false,"",false);template(true);
            });
            group("M03 frozen account and current authorization govern new and replay actions",()->{
                Map<String,Object>d=fresh("identity");var submitReplay=cmd(d,"unused");var request=actionBody(d,"APPROVE","m03-original-extra-approve");config(false,"",true);unchanged(()->reject(403,()->call("approval",extraAfter,request)));config(false,"",false);d=call("approval",extraBefore,request);unchanged(()->call("approval",extraBefore,request));config(false,"approval.review",false);unchanged(()->reject(403,()->call("approval",extraBefore,request)));config(false,"",false);
                d=current(d);var withdraw=actionBody(d,"WITHDRAW","m03-withdraw-owner-replay");d=call("approval",filler,withdraw);config(false,"demand.write",false);unchanged(()->reject(403,()->call("approval",filler,withdraw)));config(false,"",false);
            });
            group("M03 combined required duties do not swallow supplementary nodes",()->{
                config(true,"",false);Map<String,Object>d=fresh("combined");d=act(d,extraBefore,"APPROVE");eq("required-combined",node(d));d=act(d,reviewer,"APPROVE_COMBINED");eq("extra-after",node(d));eq("EXTRA_PENDING",map(d.get("approval")).get("status"));d=act(d,extraAfter,"APPROVE");eq("ready",d.get("queue"));d=call("accept",team,acceptBody(d));eq("accepted",d.get("queue"));
            });
            group("M03 current template damage cannot poison old frozen actions or history",()->{
                Map<String,Object>d=fresh("isolation");long id=n(d,"id");String hash=Objects.toString(Db.one("SELECT head_hash FROM approval_template_heads WHERE business_kind='M03_DEMAND' AND organization_code='001'").get("head_hash"));Db.exec("UPDATE approval_template_heads SET head_hash=? WHERE business_kind='M03_DEMAND' AND organization_code='001'","f".repeat(64));try{d=act(d,extraBefore,"APPROVE");d=act(d,reviewer,"APPROVE_COMBINED");d=act(d,extraAfter,"APPROVE");eq("ready",d.get("queue"));var future=draft("new-when-corrupt");unchanged(()->reject(503,()->call("submit",filler,cmd(future,"submit-when-corrupt"))));}finally{Db.exec("UPDATE approval_template_heads SET head_hash=? WHERE business_kind='M03_DEMAND' AND organization_code='001'",hash);}Db.get().close();eq("ready",WorkflowIntegration.detail(id,filler).get("queue"));
                String raw=Objects.toString(Db.one("SELECT state_json FROM approval_workflows WHERE id=?",id).get("state_json"));var broken=map(Json.parse(raw));map(map(broken.get("policy")).get("approvalTemplate")).put("organization_code","002");Db.exec("UPDATE approval_workflows SET state_json=? WHERE id=?",Json.write(broken),id);try{unchanged(()->reject(409,()->WorkflowIntegration.detail(id,filler)));}finally{Db.exec("UPDATE approval_workflows SET state_json=? WHERE id=?",raw,id);}
            });
            System.out.println("NextApprovalTemplate M03: "+groups+" groups / "+checks+" checks; real Auth/M01/workflow/acceptance/notification, no listener.");
        }finally{Db.exec("SHUTDOWN");}
    }
}
