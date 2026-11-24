package com.training;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static com.training.M08FormalTest.*;

/** Fresh synthetic H2, real Auth/M01/M08. M07 is the existing explicit reviewed-provider contract double. */
public final class B1SummaryReviewPolicyTest {
    static long sequence;
    static Map<String,Object> policy()throws Exception{return SummaryReviewPolicyIntegration.read(s(1));}
    static Map<String,Object> preview(String order)throws Exception{return SummaryReviewPolicyIntegration.preview(s(1),Map.of("expected_version",policy().get("revision"),"review_order",order,"reason","SYNTHETIC 明确调整未来提交顺序"));}
    static Map<String,Object> request(Map<String,Object> preview){return Map.of("review_token",preview.get("review_token"),"request_id","synthetic-policy-"+(++sequence));}
    static Map<String,Object> confirm(Map<String,Object> body)throws Exception{return SummaryReviewPolicyIntegration.confirm(s(1),body);}
    static String events(long project)throws Exception{return c(Db.query("SELECT * FROM m08_summary_workflow_events WHERE project_id=? ORDER BY version",project));}
    static void properties(String order,String version){if(order==null)System.clearProperty("training.summary.review.order");else System.setProperty("training.summary.review.order",order);if(version==null)System.clearProperty("training.summary.review.policyVersion");else System.setProperty("training.summary.review.policyVersion",version);}
    static void connectFeedback(){TrainingSummariesFeedbackSource.connect(new TrainingSummariesFeedbackSource.ReviewedProvider(){public Map<String,Object> capture(Auth.Session session,long project,String org){return feedback(project,org);}public void validate(Object value)throws Exception{var copy=new LinkedHashMap<>(m(value));Object digest=copy.remove("source_version");if(!Objects.equals(digest,TrainingSummariesWorkflow.hash(c(copy))))throw new Api.ApiException(409,"synthetic provider digest invalid");}});}
    @SuppressWarnings("unchecked") static Map<String,Object> previews()throws Exception{var field=SummaryReviewPolicyIntegration.class.getDeclaredField("PREVIEWS");field.setAccessible(true);return (Map<String,Object>)field.get(null);}
    static void expire(Map<String,Object> value)throws Exception {var all=previews();String token=value.get("review_token").toString();Object review=all.get(token);Class<?> type=review.getClass();var components=type.getRecordComponents();Class<?>[] types=new Class<?>[components.length];Object[] args=new Object[components.length];for(int i=0;i<args.length;i++){types[i]=components[i].getType();var accessor=components[i].getAccessor();accessor.setAccessible(true);args[i]=components[i].getName().equals("expires")?1L:accessor.invoke(review);}var constructor=type.getDeclaredConstructor(types);constructor.setAccessible(true);all.put(token,constructor.newInstance(args));}
    public static void main(String[] args)throws Exception {
        if(args.length==1&&args[0].equals("--raw-edges")){
            for(String raw:List.of("1.0000000000000001","1e-400"))rejects(400,()->SummaryReviewPolicyIntegration.parseBody(("{\"reason\":\"comma, { } and quote \\\" safe\",\"expected_\\u0076ersion\":"+raw+"}").getBytes(StandardCharsets.UTF_8)));
            eq(7L,SummaryReviewPolicyIntegration.parseBody("{\"reason\":\"comma, { } and quote \\\" safe\",\"expected_\\u0076ersion\":7}".getBytes(StandardCharsets.UTF_8)).get("expected_version"));
            System.out.println("B1 raw escaped-key precision: "+checks+" checks passed; no database or listener.");return;
        }
        Path data=Path.of(args[0]).toAbsolutePath().normalize();if(!data.toString().contains("approval-config-B1")||Files.isSymbolicLink(data))throw new AssertionError("private B1 path only");try(var entries=Files.list(data)){if(entries.findAny().isPresent())throw new AssertionError("fresh isolated DB only");}
        try{fixture(data);connectFeedback();
            group("empty tables and live global admin; no M01 binding required",()->{
                unchanged(()->{eq(0L,n(policy().get("revision")));eq("DEPLOYMENT",policy().get("authority"));eq(false,policy().get("configured"));eq(0,SummaryReviewPolicyIntegration.history(s(1),0,20).get("total"));rejects(401,()->SummaryReviewPolicyIntegration.read(null));for(int who:List.of(2,5,6)){rejects(403,()->SummaryReviewPolicyIntegration.read(s(who)));rejects(403,()->SummaryReviewPolicyIntegration.preview(s(who),Map.of()));rejects(403,()->SummaryReviewPolicyIntegration.confirm(s(who),Map.of()));}});
                eq(null,OrganizationAccessStore.configuration().accountBindings().stream().filter(b->b.accountId()==1).findFirst().orElse(null),"admin is intentionally unbound");
            });
            group("strict scalar JSON and preview has no persistence",()->{
                unchanged(()->{var p=preview("UNORDERED");eq(true,p.get("changed"));eq(true,p.get("authority_change"));eq(null,m(p.get("before")).get("review_order"));
                    for(String invalid:List.of("{\"expected_version\":0,\"expected_version\":1}","{\"reason\":\"\\ud800\"}","{\"reason\":{}}","[]"))rejects(400,()->SummaryReviewPolicyIntegration.parseBody(invalid.getBytes(StandardCharsets.UTF_8)));
                    for(String raw:List.of("1.0000000000000001","1e-400","9007199254740992","-0.0000000000000001"))rejects(400,()->SummaryReviewPolicyIntegration.parseBody(("{\"expected_version\":"+raw+"}").getBytes(StandardCharsets.UTF_8)));
                    eq(1L,SummaryReviewPolicyIntegration.parseBody("{\"expected_version\":1}".getBytes(StandardCharsets.UTF_8)).get("expected_version"));
                    rejects(400,()->SummaryReviewPolicyIntegration.parseBody(new byte[]{(byte)0xc3,(byte)0x28}));rejects(413,()->SummaryReviewPolicyIntegration.parseBody((" ".repeat(16384)+"{}").getBytes(StandardCharsets.UTF_8)));
                    check(SummaryReviewPolicyIntegration.parseBody("{\"reason\":\"合成🌿\"}".getBytes(StandardCharsets.UTF_8)).get("reason").equals("合成🌿"),"valid paired Unicode remains intact");
                    for(Object order:List.of("COMBINED","AUTO",42))rejects(400,()->SummaryReviewPolicyIntegration.preview(s(1),Map.of("expected_version",0,"review_order",order,"reason","合成")));rejects(400,()->SummaryReviewPolicyIntegration.preview(s(1),Map.of("expected_version","0","review_order","UNORDERED","reason","合成")));
                });
            });
            group("deployment compatibility, preview session expiry and changed source",()->{
                properties("BRANCH_THEN_BP","SYNTHETIC-DEPLOY-1");var p=preview("BRANCH_THEN_BP");eq(true,p.get("changed"),"same order still explicitly changes authority");properties("UNORDERED","SYNTHETIC-DEPLOY-2");unchanged(()->rejects(409,()->confirm(request(p))));properties("BRANCH_THEN_BP","SYNTHETIC-DEPLOY-1");
                var exp=preview("UNORDERED");expire(exp);unchanged(()->rejects(409,()->confirm(request(exp))));var bound=preview("UNORDERED");Auth.Session another=Auth.get(Auth.login("u1",PASSWORD));unchanged(()->rejects(409,()->SummaryReviewPolicyIntegration.confirm(another,request(bound))));
                save(2,101,"旧提交顺序冻结");TrainingSummariesWorkflow.mutate("submit",s(2),body(101));eq("BRANCH_THEN_BP",flow(2,101).get("review_order"));
            });
            final Map<String,Object>[] firstRequest=new Map[1];final String[] firstVersion=new String[1];
            group("first explicit database publication with blank admin display name",()->{
                Db.exec("UPDATE users SET name='' WHERE id=1");sessions.put(1,Auth.get(Auth.login("u1",PASSWORD)));var p=preview("UNORDERED");firstRequest[0]=request(p);var receipt=confirm(firstRequest[0]);firstVersion[0]=receipt.get("policy_version").toString();eq(true,receipt.get("saved"));eq(1L,n(receipt.get("result_revision")));eq("DATABASE",policy().get("authority"));eq("u1",m(((List<?>)SummaryReviewPolicyIntegration.history(s(1),0,20).get("items")).get(0)).get("actor_name"));
                unchanged(()->{eq(true,confirm(firstRequest[0]).get("replayed"));rejects(409,()->confirm(Map.of("request_id",firstRequest[0].get("request_id"),"review_token","a".repeat(64))));rejects(409,()->review(6,101,"BP","APPROVE"));});
                save(2,102,"新提交使用无先后");TrainingSummariesWorkflow.mutate("submit",s(2),body(102));eq("UNORDERED",flow(2,102).get("review_order"));review(6,102,"BP","APPROVE");eq("APPROVED",review(5,102,"BRANCH","APPROVE").get("status"));
            });
            group("no-op keeps exact policy version and persistent replay is read only",()->{
                var p=preview("UNORDERED");eq(false,p.get("changed"));eq(firstVersion[0],m(p.get("after")).get("policy_version"));var req=request(p);eq(false,confirm(req).get("saved"));eq(1L,n(policy().get("revision")));unchanged(()->{eq(true,confirm(req).get("replayed"));rejects(409,()->confirm(request(p)));});
            });
            group("two confirmations use CAS; old replay identifies its original result",()->{
                var one=request(preview("BRANCH_THEN_BP"));var two=request(preview("BRANCH_THEN_BP"));ExecutorService workers=Executors.newFixedThreadPool(2);try{var tasks=workers.invokeAll(List.of((Callable<Integer>)()->attempt(one),()->attempt(two)));List<Integer> codes=new ArrayList<>();for(var task:tasks)codes.add(task.get());Collections.sort(codes);eq(List.of(200,409),codes);}finally{workers.shutdown();check(workers.awaitTermination(10,TimeUnit.SECONDS),"workers stopped");}
                unchanged(()->{var replay=confirm(firstRequest[0]);eq(1L,n(replay.get("result_revision")));eq(2L,n(replay.get("current_revision")));eq("UNORDERED",replay.get("review_order"));eq(firstVersion[0],replay.get("policy_version"));});
            });
            group("restart keeps database authority even with invalid deployment",()->{
                Db.get().close();TrainingSummariesIntegration.init();previews().clear();properties("INVALID","bad value");unchanged(()->{eq("DATABASE",policy().get("authority"));eq(false,m(policy().get("deployment")).get("valid"));eq(null,m(policy().get("deployment")).get("review_order"));eq(true,confirm(firstRequest[0]).get("replayed"));eq("BRANCH_THEN_BP",SummaryReviewPolicyIntegration.resolve().order());});
            });
            group("corrupt current policy cannot reroute new work or roll back old frozen review and Word",()->{
                String head=Db.one("SELECT head_hash FROM m08_review_policy_head").get("head_hash").toString();Db.exec("UPDATE m08_review_policy_head SET head_hash=?","f".repeat(64));
                try{unchanged(()->{rejects(503,()->policy());eq("SUBMITTED",flow(2,101).get("status"));eq(true,m(flow(5,101).get("capabilities")).get("review"));});save(2,103,"新普通提交应拒绝");unchanged(()->rejects(503,()->TrainingSummariesWorkflow.mutate("submit",s(2),body(103))));review(5,101,"BRANCH","APPROVE");eq("APPROVED",review(6,101,"BP","APPROVE").get("status"));check(TrainingSummariesWorkflow.export(s(7),body(101)).bytes().length>0,"legal frozen Word export response survives current config fault");}
                finally{Db.exec("UPDATE m08_review_policy_head SET head_hash=?",head);}
                var latest=Db.one("SELECT version,payload_json FROM m08_review_policy_revisions ORDER BY version DESC LIMIT 1");String original=latest.get("payload_json").toString();var corrupt=new LinkedHashMap<>(m(Json.parse(original)));corrupt.put("created_at","NOT-A-TIMESTAMP");Db.exec("UPDATE m08_review_policy_revisions SET payload_json=? WHERE version=?",c(corrupt),latest.get("version"));
                try{unchanged(()->{rejects(503,()->policy());eq("APPROVED",flow(2,101).get("status"));rejects(503,()->TrainingSummariesWorkflow.mutate("submit",s(2),body(103)));});check(TrainingSummariesWorkflow.export(s(7),body(101)).bytes().length>0,"malformed policy timestamp cannot poison legal frozen export");}finally{Db.exec("UPDATE m08_review_policy_revisions SET payload_json=? WHERE version=?",original,latest.get("version"));}
                String old=events(101);var p=preview("UNORDERED");confirm(request(p));eq(old,events(101),"new configuration never writes old business events");save(2,101,"新正文重提取新规则");TrainingSummariesWorkflow.mutate("submit",s(2),body(101));eq("UNORDERED",flow(2,101).get("review_order"));
            });
            group("unrelated reads and new confirmations also detect receipt corruption",()->{
                var row=Db.one("SELECT * FROM m08_review_policy_requests WHERE account_id=1 AND request_id=?",firstRequest[0].get("request_id"));Db.exec("UPDATE m08_review_policy_requests SET receipt_hash=? WHERE account_id=1 AND request_id=?","f".repeat(64),row.get("request_id"));try{unchanged(()->{rejects(503,()->policy());rejects(503,()->SummaryReviewPolicyIntegration.history(s(1),0,20));rejects(503,()->preview("UNORDERED"));rejects(503,()->confirm(firstRequest[0]));});}finally{Db.exec("UPDATE m08_review_policy_requests SET receipt_hash=? WHERE account_id=1 AND request_id=?",row.get("receipt_hash"),row.get("request_id"));}
            });
            group("preview revocation and failed publication leave no partial writes",()->{
                var p=preview("BRANCH_THEN_BP");Auth.Session old=s(1);Db.exec("UPDATE users SET role='viewer' WHERE id=1");unchanged(()->rejects(403,()->SummaryReviewPolicyIntegration.confirm(old,request(p))));Db.exec("UPDATE users SET role='admin' WHERE id=1");sessions.put(1,Auth.get(Auth.login("u1",PASSWORD)));
                String audit=c(SummaryReviewPolicyIntegration.history(s(1),0,20));var next=request(preview("BRANCH_THEN_BP"));Db.exec("CREATE TRIGGER b1_revoke AFTER INSERT ON m08_review_policy_revisions FOR EACH ROW CALL 'com.training.B1SummaryReviewPolicyTest$Revoke'");try{unchanged(()->rejects(401,()->confirm(next)));}finally{Db.exec("DROP TRIGGER b1_revoke");}sessions.put(1,Auth.get(Auth.login("u1",PASSWORD)));eq(audit,c(SummaryReviewPolicyIntegration.history(s(1),0,20)));
            });
            System.out.println("B1SummaryReviewPolicy: "+groups+" groups, "+checks+" checks passed; synthetic direct Auth/M01/M08; no listeners.");
        }finally{properties(null,null);Db.get().close();}
    }
    static int attempt(Map<String,Object> body)throws Exception{try{confirm(body);return 200;}catch(Api.ApiException e){return e.code;}}
    public static final class Revoke implements org.h2.api.Trigger {public void fire(java.sql.Connection connection,Object[] oldRow,Object[] newRow)throws java.sql.SQLException{try(var statement=connection.createStatement()){statement.executeUpdate("UPDATE users SET status=0 WHERE id=1");}}}
}
