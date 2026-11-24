package com.training;

import java.nio.file.*;
import java.util.*;
import static com.training.OrganizationAccess.*;
import static com.training.ApprovalTemplateDefinition.*;

/** Synthetic direct calls only: no listener, real account, business data or model transport. */
public final class ApprovalTemplatePublishPreflightTest {
    private static final String PASS="SYNTHETIC-PREFLIGHT-ONLY";
    private static Auth.Session admin;
    private static int checks,sequence;
    private interface Job{void run()throws Exception;}
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value){return (Map<String,Object>)value;}
    private static long number(Object value){return ((Number)value).longValue();}
    private static String request(){return "preflight-request-"+(++sequence);}
    private static void rejected(int code,Job work)throws Exception{checks++;try{work.run();throw new AssertionError("expected rejection "+code);}catch(Api.ApiException e){if(e.code!=code)throw new AssertionError("expected "+code+" got "+e.code+": "+e.getMessage());}}
    private static String tables()throws Exception{
        Map<String,Object> result=new TreeMap<>();for(var row:Db.query("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE'")){
            String table=row.get("table_name").toString();if(!table.matches("[A-Z_0-9]+"))throw new AssertionError();
            result.put(table,Db.query("SELECT * FROM \""+table+"\"").stream().map(TrainingSummariesWorkflow::canonical).sorted().toList());
        }return TrainingSummariesWorkflow.canonical(result);
    }
    private static void unchanged(Job work)throws Exception{String before=tables();work.run();check(before.equals(tables()),"preview or rejected command changes no table");}
    private static void configure(String leader,String bp,boolean selfExtra,boolean crossOrg,boolean combinedEvidence)throws Exception{
        Set<String> roles=Set.of("WRITER","LEADER","BP");RelationRule optional=new RelationRule(false,roles,false,false);List<Grant> grants=new ArrayList<>();
        for(String role:roles)for(String resource:List.of("demand.read","summary.read","delivery.read","survey.read"))grants.add(new Grant(role+resource,role,resource,Action.VIEW,Effect.ALLOW,Scope.NAMED_ORGS,Set.of("001")));
        for(String resource:List.of("demand.write","summary.edit"))grants.add(new Grant("write"+resource,"WRITER",resource,Action.HANDLE,Effect.ALLOW,crossOrg?Scope.NAMED_ORGS:Scope.OWN_ORG,crossOrg?Set.of("001"):Set.of()));
        for(String role:List.of("LEADER","BP"))grants.add(new Grant(role+"approval",role,"approval.review",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()));
        grants.add(new Grant("summary-branch","LEADER","summary.review.branch",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()));
        grants.add(new Grant("summary-bp","BP","summary.review.bp",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of()));
        List<Person> people=List.of(
            new Person("P2","001",selfExtra?Set.of("001"):Set.of(),leader,bp,selfExtra?Set.of("WRITER","LEADER"):Set.of("WRITER"),true),
            new Person("P3","002",Set.of("001"),null,null,Set.of("LEADER","BP"),true,Map.of("LEADER",Set.of("001"),"BP",Set.of("001"))),
            new Person("P4","002",Set.of("001"),null,null,Set.of("BP"),true),
            new Person("P5","002",Set.of("001"),null,null,Set.of("LEADER"),true),
            new Person("P6","002",Set.of("001"),null,null,Set.of("BP"),true),
            new Person("P8","002",Set.of(),crossOrg?"P5":null,crossOrg?"P4":null,Set.of("WRITER"),true));
        var bindings=List.of(2,3,4,5,6,8).stream().map(i->new AccountBinding(i,"P"+i,true)).toList();
        var c=new Configuration("PREFLIGHT-"+(++sequence),new CodeRules("[0-9]{3}","P[0-9]+","[A-Z]+"),roles,List.of(new Organization("001",null,true),new Organization("002",null,true)),people,roles.stream().map(r->new RoleRelations(r,optional,optional)).toList(),bindings,grants,combinedEvidence?List.of(new CombinedApprovalAssignment("001","P3","LEADER","BP","SYNTHETIC-EXPLICIT-DUTIES")):List.of());
        Configuration old=OrganizationAccessStore.configuration();OrganizationAccessStore.publish(admin,old==null?null:old.version(),c);admin=Auth.get(Auth.login("admin",PASS));
    }
    private static Map<String,Object> definition(String person,String role){return Map.of("format","APPROVAL-TEMPLATE-1","core_order","BRANCH_THEN_BP","before_nodes",person==null?List.of():List.of(Map.of("node_id","extra-before","title","合成补充审批","review_role",role,"person_code",person)),"after_nodes",List.of());}
    private static Map<String,Object> draft(Kind kind,String person,String role)throws Exception{
        long revision=number(ApprovalTemplatesIntegration.read(admin,kind,"001").get("head_revision"));
        return ApprovalTemplatesIntegration.command(admin,kind,"draft",Map.of("organization_code","001","expected_revision",revision,"definition",definition(person,role),"reason","合成人员发布预检","request_id",request()));
    }
    private static Map<String,Object> preview(Kind kind)throws Exception{
        long revision=number(ApprovalTemplatesIntegration.read(admin,kind,"001").get("head_revision"));
        return ApprovalTemplatesIntegration.command(admin,kind,"preview",Map.of("organization_code","001","expected_revision",revision));
    }
    private static Map<String,Object> publish(Kind kind,Map<String,Object> view)throws Exception{return ApprovalTemplatesIntegration.command(admin,kind,"publish",Map.of("review_token",view.get("review_token"),"request_id",request()));}
    private static void blocked(Kind kind,String person,String role,String expectedRole)throws Exception{
        draft(kind,person,role);unchanged(()->{var v=preview(kind);var p=object(v.get("preflight"));check(Boolean.FALSE.equals(p.get("publishable")),"conflict blocks publication");check(v.get("review_token")==null&&v.get("expires_at")==null,"blocked preview has no token");
            check(((List<?>)p.get("conflicts")).stream().map(ApprovalTemplatePublishPreflightTest::object).anyMatch(i->"P2".equals(i.get("submitter_code"))&&expectedRole.equals(i.get("conflict_role"))),"exact affected submitter and role");
            rejected(409,()->ApprovalTemplatesIntegration.command(admin,kind,"publish",Map.of("review_token","f".repeat(64),"request_id",request())));});
        check(ApprovalTemplatesIntegration.read(admin,kind,"001").get("draft")!=null,"blocked draft retained");
    }
    private static void run(Kind kind)throws Exception{
        configure("P3","P4",false,false,true);draft(kind,"P5","BRANCH");Map<String,Object> good=preview(kind),p=object(good.get("preflight"));
        check(Boolean.TRUE.equals(p.get("publishable"))&&number(p.get("checked_submitters"))==1,"same-name extra safe; unauthorized other-org missing relations excluded");
        var body=Map.<String,Object>of("review_token",good.get("review_token"),"request_id",request());var receipt=ApprovalTemplatesIntegration.command(admin,kind,"publish",body);check(Boolean.TRUE.equals(receipt.get("saved")),"safe publication persists");
        unchanged(()->check(Boolean.TRUE.equals(ApprovalTemplatesIntegration.command(admin,kind,"publish",body).get("replayed")),"idempotent publication replay"));
        var published=ApprovalTemplatesIntegration.resolve(kind,"001");Person submitter=OrganizationAccessStore.configuration().people().get(0);
        var frozen=ApprovalTemplatesIntegration.freeze(published,submitter,2,"P3","P4",false);check(frozen.nodes().size()==3&&frozen.node("required-branch")!=null&&frozen.node("required-bp")!=null,"all supplementary and required gates preserved");
        draft(kind,"P5","BRANCH");check(Boolean.FALSE.equals(publish(kind,preview(kind)).get("saved")),"unchanged template stays no-op");
        blocked(kind,"P3","BRANCH","BRANCH");blocked(kind,"P4","BP","BP");
        configure("P3","P4",true,false,true);blocked(kind,"P2","BRANCH","SUBMITTER");
        configure(null,"P4",false,false,true);draft(kind,"P5","BRANCH");unchanged(()->{var v=preview(kind);var report=object(v.get("preflight"));check(Boolean.FALSE.equals(report.get("publishable"))&&!((List<?>)report.get("unresolved")).isEmpty(),"authorized submitter missing leader is explicit and blocked");check(v.get("review_token")==null,"unresolved relation gets no token");});
        configure("P3","P3",false,false,false);draft(kind,"P5","BRANCH");check(!((List<?>)object(preview(kind).get("preflight")).get("unresolved")).isEmpty(),"equal roles without explicit combined proof not passed");
        configure("P3","P3",false,false,true);check(Boolean.TRUE.equals(object(preview(kind).get("preflight")).get("publishable")),"explicit combined evidence accepted");blocked(kind,"P3","BRANCH","BRANCH");
        configure("P3","P4",false,true,true);draft(kind,"P5","BRANCH");var cross=object(preview(kind).get("preflight"));check(number(cross.get("checked_submitters"))==2,"cross-organization authorized submitter included");check(((List<?>)cross.get("conflicts")).stream().map(ApprovalTemplatePublishPreflightTest::object).anyMatch(i->"P8".equals(i.get("submitter_code"))),"cross-organization person's required leader collision shown");
        configure("P3","P4",false,false,true);draft(kind,"P5","BRANCH");var stale=preview(kind);Db.exec("UPDATE users SET status=0 WHERE id=3");try{unchanged(()->rejected(409,()->publish(kind,stale)));}finally{Db.exec("UPDATE users SET status=1 WHERE id=3");}
        var disabledSubmitter=preview(kind);Db.exec("UPDATE users SET status=0 WHERE id=2");try{unchanged(()->rejected(409,()->publish(kind,disabledSubmitter)));}finally{Db.exec("UPDATE users SET status=1 WHERE id=2");}
        var changed=preview(kind);configure("P5","P4",false,false,true);unchanged(()->rejected(409,()->publish(kind,changed)));check(!((List<?>)object(preview(kind).get("preflight")).get("conflicts")).isEmpty(),"new preview exposes changed relationship");
        var latest=ApprovalTemplatesIntegration.resolve(kind,"001");Person now=OrganizationAccessStore.configuration().people().get(0);rejected(409,()->ApprovalTemplatesIntegration.freeze(latest,now,2,"P5","P4",false));check(frozen.node("required-branch").person().equals("P3"),"prior frozen copy remains unchanged");
        System.out.println("PASS "+kind+" publication preflight and stale-state guards");
    }
    public static void main(String[] args)throws Exception{
        Path data=Path.of(args[0]).toAbsolutePath().normalize();if(!data.toString().contains("approval-template")||!Files.isDirectory(data)||Files.isSymbolicLink(data))throw new IllegalArgumentException("isolated existing directory required");try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("empty synthetic DB only");}
        System.setProperty("data.dir",data.toString());System.setProperty("bootstrap.demo","false");System.setProperty("bootstrap.admin.password",PASS);Db.init();
        try{for(int id:List.of(2,3,4,5,6,8))Db.exec("INSERT INTO users(id,username,password,name,role,status) VALUES(?,?,?,?,?,1)",id,"synthetic-preflight-"+id,Auth.hash(PASS),id==3||id==5?"同名合成人员":"合成人员"+id,"viewer");admin=Auth.get(Auth.login("admin",PASS));for(Kind kind:Kind.values())run(kind);System.out.println("ApprovalTemplatePublishPreflight: "+checks+" checks; synthetic direct calls, no listener.");}finally{Db.exec("SHUTDOWN");}
    }
}
