package com.training;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.training.M08FormalTest.*;
import static com.training.OrganizationAccess.*;
import static com.training.ApprovalTemplateDefinition.*;

/** Local synthetic direct integration; no listener. M08 source provider is an explicit test double. */
public final class NextApprovalTemplateTest {
    static Map<String,Object> submitReplay,approveReplay;static long seq;static final String PASS="SYNTHETIC-TEMPLATE-ONLY";static Map<Integer,Auth.Session> demandUsers=new HashMap<>();
    static Map<String,Object> definition(String order,String before,String after){return Map.of("format","APPROVAL-TEMPLATE-1","core_order",order,"before_nodes",before==null?List.of():List.of(extra("extra-before","补充预审",before)),"after_nodes",after==null?List.of():List.of(extra("extra-after","补充终审",after)));}
    static Map<String,Object> extra(String id,String title,String person){return Map.of("node_id",id,"title",title,"review_role","BRANCH","person_code",person);}
    static Auth.Session admin(){return demandUsers.isEmpty()?s(1):demandUsers.get(1);}
    static Map<String,Object> draft(Kind kind,Map<String,Object> definition)throws Exception{long revision=n(ApprovalTemplatesIntegration.read(admin(),kind,"001").get("head_revision"));return ApprovalTemplatesIntegration.command(admin(),kind,"draft",Map.of("organization_code","001","expected_revision",revision,"request_id","template-draft-"+(++seq),"definition",definition,"reason","仅本地合成模板验证"));}
    static Map<String,Object> publish(Kind kind,Map<String,Object> definition)throws Exception{draft(kind,definition);var state=ApprovalTemplatesIntegration.read(admin(),kind,"001");var preview=ApprovalTemplatesIntegration.command(admin(),kind,"preview",Map.of("organization_code","001","expected_revision",state.get("head_revision")));eq(kind==Kind.M03_DEMAND?"FUTURE_M03_INSTANCES":"FUTURE_M08_SUBMISSIONS",preview.get("applies_to"));return ApprovalTemplatesIntegration.command(admin(),kind,"publish",Map.of("review_token",preview.get("review_token"),"request_id","template-publish-"+(++seq)));}
    static void feedbackProvider(){TrainingSummariesFeedbackSource.connect(new TrainingSummariesFeedbackSource.ReviewedProvider(){public Map<String,Object>capture(Auth.Session s,long p,String org){return feedback(p,org);}public void validate(Object raw)throws Exception{var value=new LinkedHashMap<>(m(raw));Object digest=value.remove("source_version");if(!Objects.equals(digest,TrainingSummariesWorkflow.hash(c(value))))throw new Api.ApiException(409,"synthetic provider digest");}});}
    static void summaryConfig(boolean combined,boolean revokeExtra)throws Exception{
        Configuration b=config("0005",combined?"0005":"0006",true,true);List<Person>people=new ArrayList<>();for(Person p:b.people()){
            if(p.personCode().equals("0009"))people.add(new Person("0009","001",Set.of(),null,null,Set.of("BRANCH"),true));
            else if(p.personCode().equals("0005")&&combined)people.add(new Person("0005","001",Set.of("001"),null,null,Set.of("BRANCH","BP"),true,Map.of("BRANCH",Set.of("001"),"BP",Set.of("001"))));
            else if(p.personCode().equals("0008")&&revokeExtra)people.add(new Person("0008","001",Set.of(),null,null,Set.of("WRITER"),true));else people.add(p);
        }
        var cfg=new Configuration(b.version(),b.codeRules(),b.roleCodes(),b.organizations(),people,b.relations(),b.accountBindings(),b.grants(),combined?List.of(new CombinedApprovalAssignment("001","0005","BRANCH","BP","SYNTHETIC-ONLY-TEMPLATE-COMBINED")):List.of());
        OrganizationAccessStore.publish(Auth.get(Auth.login("u1",PASSWORD)),OrganizationAccessStore.configuration().version(),cfg);login();
    }
    static Map<String,Object> summaryReview(long project,int who,String id,String role,String decision)throws Exception{Map<String,Object> body=reviewBody(project,role,decision,"本地合成节点办理");body.put("node_id",id);return TrainingSummariesWorkflow.mutate("review",s(who),body);}
    static void summary(Path data,Path out)throws Exception{
        fixture(data);feedbackProvider();summaryConfig(false,false);
        group("strict raw template language and no implicit permissions",()->{
            for(String raw:List.of("{\"expected_revision\":1.0000000000000001}","{\"expected_\\u0072evision\":1e-400}","{\"reason\":\"\\ud800\"}","{\"expected_revision\":0,\"expected_\\u0072evision\":0}"))rejects(400,()->ApprovalTemplatesIntegration.parseBody(raw.getBytes(StandardCharsets.UTF_8)));
            rejects(400,()->ApprovalTemplatesIntegration.parseBody(new byte[]{'{','"','x','"',':','"',(byte)0xc0,(byte)0x80,'"','}'}));
            rejects(413,()->ApprovalTemplatesIntegration.parseBody(new byte[MAX_BODY_FOR_TEST()]));
            unchanged(()->rejects(403,()->ApprovalTemplatesIntegration.read(s(5),Kind.M08_SUMMARY,"001")));
            rejects(400,()->parse(Kind.M03_DEMAND,definition("UNORDERED",null,null)));rejects(400,()->parse(Kind.M08_SUMMARY,definition("BRANCH_THEN_BP","0008","0008")));
            unchanged(()->rejects(409,()->draft(Kind.M08_SUMMARY,definition("BRANCH_THEN_BP","0003",null))));
        });
        group("draft preview publication no-op and complete receipt replay",()->{
            var initial=draft(Kind.M08_SUMMARY,definition("BRANCH_THEN_BP","0008","0009"));eq(0L,n(initial.get("current_active_revision")));eq(null,ApprovalTemplatesIntegration.resolve(Kind.M08_SUMMARY,"001"));
            var state=ApprovalTemplatesIntegration.read(admin(),Kind.M08_SUMMARY,"001");var preview=ApprovalTemplatesIntegration.command(admin(),Kind.M08_SUMMARY,"preview",Map.of("organization_code","001","expected_revision",state.get("head_revision")));var request=Map.<String,Object>of("review_token",preview.get("review_token"),"request_id","template-first-publish");var result=ApprovalTemplatesIntegration.command(admin(),Kind.M08_SUMMARY,"publish",request);eq(2L,n(result.get("current_active_revision")));unchanged(()->eq(true,ApprovalTemplatesIntegration.command(admin(),Kind.M08_SUMMARY,"publish",request).get("replayed")));
            var noOp=publish(Kind.M08_SUMMARY,definition("BRANCH_THEN_BP","0008","0009"));eq(false,noOp.get("saved"));eq(2L,n(noOp.get("current_revision")));eq(null,ApprovalTemplatesIntegration.resolve(Kind.M03_DEMAND,"001"));
        });
        group("M08 pre and post approvals execute with no export bypass",()->{
            save(2,101,"含补充节点总结");submitReplay=body(101);TrainingSummariesWorkflow.mutate("submit",s(2),submitReplay);eq("extra-before",m(flow(8,101).get("capabilities")).get("review_node_id"));
            unchanged(()->{rejects(409,()->summaryReview(101,5,"required-branch","BRANCH","APPROVE"));rejects(403,()->summaryReview(101,5,"extra-before","EXTRA","APPROVE"));rejects(409,()->TrainingSummariesWorkflow.export(s(7),body(101)));});
            approveReplay=reviewBody(101,"EXTRA","APPROVE","本地合成节点办理");approveReplay.put("node_id","extra-before");TrainingSummariesWorkflow.mutate("review",s(8),approveReplay);summaryReview(101,5,"required-branch","BRANCH","APPROVE");summaryReview(101,6,"required-bp","BP","APPROVE");eq("SUBMITTED",flow(2,101).get("status"));unchanged(()->rejects(409,()->TrainingSummariesWorkflow.export(s(7),body(101))));summaryReview(101,9,"extra-after","EXTRA","APPROVE");eq("APPROVED",flow(2,101).get("status"));eq(4,((List<?>)flow(2,101).get("decisions")).size());var word=TrainingSummariesWorkflow.export(s(7),body(101));String xml=unzip(word.bytes()).get("word/document.xml");check(xml.contains("补充预审")&&xml.contains("补充终审")&&xml.contains("extra-before")&&xml.contains("extra-after"),"Word records all actual extra nodes");Files.write(out.resolve("template-summary.docx"),word.bytes());
        });
        group("same node id future rename never changes frozen submission",()->{
            save(2,102,"模板冻结总结");TrainingSummariesWorkflow.mutate("submit",s(2),body(102));String frozen=c(flow(2,102).get("approval_template"));var changed=new LinkedHashMap<>(definition("UNORDERED","0008",null));changed.put("before_nodes",List.of(extra("extra-before","新版补充名称","0009")));publish(Kind.M08_SUMMARY,changed);eq(frozen,c(flow(2,102).get("approval_template")));
            summaryReview(102,8,"extra-before","EXTRA","RETURN");eq("RETURNED",flow(2,102).get("status"));unchanged(()->rejects(409,()->TrainingSummariesWorkflow.mutate("submit",s(2),body(102))));save(2,102,"修正版采用新模板");TrainingSummariesWorkflow.mutate("submit",s(2),body(102));eq("extra-before",m(flow(9,102).get("capabilities")).get("review_node_id"));summaryReview(102,9,"extra-before","EXTRA","APPROVE");summaryReview(102,6,"required-bp","BP","APPROVE");summaryReview(102,5,"required-branch","BRANCH","APPROVE");eq("APPROVED",flow(2,102).get("status"));
        });
        group("combined core cannot consume extra nodes",()->{
            summaryConfig(true,false);publish(Kind.M08_SUMMARY,definition("BRANCH_THEN_BP","0008","0009"));save(2,103,"兼任与补充节点");TrainingSummariesWorkflow.mutate("submit",s(2),body(103));summaryReview(103,8,"extra-before","EXTRA","APPROVE");summaryReview(103,5,"required-combined","COMBINED","APPROVE_COMBINED");eq("SUBMITTED",flow(2,103).get("status"));summaryReview(103,9,"extra-after","EXTRA","APPROVE");eq("APPROVED",flow(2,103).get("status"));eq(3,((List<?>)flow(2,103).get("decisions")).size());
        });
        group("template replay rechecks each source permission while RETURN remains available",()->{
            for(String resource:List.of("delivery.read","survey.read")){
                save(2,104,"来源撤权退回对照 "+resource);TrainingSummariesWorkflow.mutate("submit",s(2),body(104));
                Configuration base=OrganizationAccessStore.configuration();Configuration revoked=new Configuration("FORMAL-"+(++configCounter),base.codeRules(),base.roleCodes(),base.organizations(),base.people(),base.relations(),base.accountBindings(),base.grants().stream().filter(g->!g.resource().equals(resource)).toList(),base.combinedApprovals());OrganizationAccessStore.publish(s(1),base.version(),revoked);login();
                unchanged(()->{rejects(403,()->TrainingSummariesWorkflow.mutate("review",s(8),approveReplay));rejects(403,()->TrainingSummariesWorkflow.mutate("submit",s(2),submitReplay));});
                eq("RETURNED",summaryReview(104,8,"extra-before","EXTRA","RETURN").get("status"));summaryConfig(true,false);
            }
        });
        group("current template corruption blocks future only and current grants remain live",()->{
            save(2,104,"存量在途模板");TrainingSummariesWorkflow.mutate("submit",s(2),body(104));summaryConfig(true,true);unchanged(()->rejects(403,()->summaryReview(104,8,"extra-before","EXTRA","APPROVE")));summaryConfig(true,false);
            String hash=(String)Db.one("SELECT head_hash FROM approval_template_heads WHERE business_kind='M08_SUMMARY' AND organization_code='001'").get("head_hash");Db.exec("UPDATE approval_template_heads SET head_hash=? WHERE business_kind='M08_SUMMARY' AND organization_code='001'","f".repeat(64));
            try{unchanged(()->{eq("APPROVED",flow(2,101).get("status"));rejects(503,()->ApprovalTemplatesIntegration.read(admin(),Kind.M08_SUMMARY,"001"));});summaryReview(104,8,"extra-before","EXTRA","APPROVE");summaryReview(104,5,"required-combined","COMBINED","APPROVE_COMBINED");summaryReview(104,9,"extra-after","EXTRA","APPROVE");check(TrainingSummariesWorkflow.export(s(7),body(104)).bytes().length>0,"old frozen export survives current configuration corruption");save(2,105,"未来提交阻断");unchanged(()->rejects(503,()->TrainingSummariesWorkflow.mutate("submit",s(2),body(105))));}finally{Db.exec("UPDATE approval_template_heads SET head_hash=? WHERE business_kind='M08_SUMMARY' AND organization_code='001'",hash);}
            Db.get().close();TrainingSummariesIntegration.init();eq("APPROVED",flow(2,104).get("status"));
        });
    }
    static int MAX_BODY_FOR_TEST(){return ApprovalTemplatesIntegration.MAX_BODY+1;}
    public static void main(String[]args)throws Exception{
        Path data=Path.of(args[0]).toAbsolutePath().normalize(),out=Path.of(args[1]).toAbsolutePath().normalize();if(!data.toString().contains("approval-template")||!Files.isDirectory(data)||Files.isSymbolicLink(data))throw new IllegalArgumentException("isolated approval-template path required");try(var all=Files.list(data)){if(all.findAny().isPresent())throw new IllegalArgumentException("empty DB only");}Files.createDirectories(out);
        try{summary(data,out);System.out.println("NextApprovalTemplate M08: "+groups+" groups / "+checks+" checks; explicit M07 provider double, no listener.");}finally{Db.get().close();}
    }
}
