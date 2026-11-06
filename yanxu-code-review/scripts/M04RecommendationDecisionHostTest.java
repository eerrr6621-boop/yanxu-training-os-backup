package com.training;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.lang.reflect.*;
import java.util.*;
import static com.training.RecommendationDecision.*;

/** Reuses the existing synthetic M01/M04 setup only; never starts an HTTP server or calls a model. */
public final class M04RecommendationDecisionHostTest {
    private static int checks;
    @FunctionalInterface private interface Work {void run()throws Exception;}
    private static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
    private static void rejects(int code,Work work,String label)throws Exception {try{work.run();throw new AssertionError(label);}catch(Api.ApiException expected){check(expected.code==code,label+" actual="+expected.code);}}
    private static Object invoke(String method,Class<?>[] types,Object...args)throws Exception {var m=Class.forName("com.training.M04RecommendationQualificationTest").getDeclaredMethod(method,types);m.setAccessible(true);try{return m.invoke(null,args);}catch(InvocationTargetException e){throw (Exception)e.getCause();}}
    private static Object field(String name)throws Exception {var f=Class.forName("com.training.M04RecommendationQualificationTest").getDeclaredField(name);f.setAccessible(true);return f.get(null);}
    @SuppressWarnings("unchecked")private static List<Map<String,Object>> rows(Map<String,Object> value){return (List<Map<String,Object>>)value.get("candidates");}
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("new empty synthetic data directory required");
        // No database access before path validation performed by the existing fixture.
        invoke("setup",new Class<?>[]{Path.class},Path.of(args[0]));
        try {
            Db.exec("ALTER TABLE teachers ADD name VARCHAR(200)");Db.exec("ALTER TABLE teachers ADD field VARCHAR(200)");Db.exec("ALTER TABLE teachers ADD intro CLOB");
            Db.exec("UPDATE teachers SET name='合成同名教师',field='沟通',intro='合成基础介绍'");
            Db.exec("CREATE TABLE teacher_resumes(id BIGINT PRIMARY KEY,teacher_id BIGINT,manual_profile CLOB,extracted_text CLOB,updated_at VARCHAR(40),is_current BOOLEAN)");
            Db.exec("CREATE TABLE dispatches(id BIGINT PRIMARY KEY,project_id BIGINT,teacher_id BIGINT,subject VARCHAR(200),teach_date VARCHAR(32),start_time VARCHAR(16),end_time VARCHAR(16),status VARCHAR(16))");
            Db.exec("UPDATE users SET role='manager' WHERE id IN(2,3,4)");
            Auth.Session actor=(Auth.Session)invoke("login",new Class<?>[]{String.class},"manager"),colleague=(Auth.Session)invoke("login",new Class<?>[]{String.class},"colleague"),other=(Auth.Session)invoke("login",new Class<?>[]{String.class},"other");
            long scope=((Number)field("scope")).longValue(),version=((Number)field("version")).longValue();
            Map<String,Object> body=map("scope_id",scope,"expected_version",version,"topic","沟通","course_code","001","date","2026-11-01","start_time","09:00","end_time","12:00","mode","ONLINE","allowed_base_cities",List.of());
            rejects(400,()->RecommendationDecisionHost.parseBody(bytes("{\"scope_id\":1.00000000000000001}")),"raw precision loss rejected");
            rejects(400,()->RecommendationDecisionHost.parseBody(bytes("{\"scope_id\":1e0}")),"raw exponent rejected");
            rejects(400,()->RecommendationDecisionHost.parseBody(bytes("{\"scope_id\":9007199254740992}")),"unsafe integer rejected");
            rejects(400,()->RecommendationDecisionHost.parseBody(bytes("{\"topic\":\"\\uD800\"}")),"unpaired high surrogate rejected");
            rejects(400,()->RecommendationDecisionHost.parseBody(bytes("{\"topic\":\"\\uDC00\"}")),"unpaired low surrogate rejected");
            check("😀".equals(RecommendationDecisionHost.parseBody(bytes("{\"topic\":\"\\uD83D\\uDE00\"}")).get("topic")),"valid emoji accepted");
            Map<String,Object> invalidDate=new LinkedHashMap<>(body);invalidDate.put("date","2026-02-30");rejects(400,()->RecommendationDecisionHost.analyze(actor,invalidDate),"invalid calendar day mapped400");
            Db.exec("INSERT INTO teacher_resumes VALUES(1,104,?,?,?,TRUE)","沟通人工主题 REVIEW_PHONE_10 synthetic@example.test 123456789012345678","不可复活的提取正文 secret-old-topic","v1");
            var snippets=RecommendationDecisionHost.topicSnippets("沟通 REVIEW_PHONE_10 x@example.test 123456789012345678 "+"资料".repeat(200),"亲子 沟通");
            check(snippets.size()<=3&&snippets.stream().allMatch(v->v.length()<=180),"short topic excerpts only");
            check(!snippets.toString().contains("REVIEW_PHONE_10")&&!snippets.toString().contains("x@example.test")&&!snippets.toString().contains("123456789012345678"),"contact and ID redacted");
            check(RecommendationDecisionHost.topicSnippets("完全无关资料","沟通").isEmpty(),"no irrelevant profile head fallback");
            Evidence synthetic=evidence("SYNTHETIC",104,"PROFILE_TOPIC","合成主题","fixture:104","v1","沟通");
            Snapshot modelInput=new Snapshot("SYNTHETIC",hash("fixture"),new Request("沟通","","","","","UNKNOWN",List.of()),
                    List.of(new Candidate(104,"合成候选","在库","",List.of(),Confirmation.UNKNOWN,Confirmation.UNKNOWN,List.of(),List.of(synthetic))),true);
            List<Decision> ruleResult=decide(modelInput);
            RecommendationModelProvider untrusted=new RecommendationModelProvider(){
                public String id(){return "MOCK";}public boolean syntheticOnly(){return true;}public boolean modelVisible(){return true;}
                public Map<String,Object> infer(Map<String,Object> input){return map("suggestions",List.of(map("candidate_id","teacher:104","evidence_ids",List.of(synthetic.id()),"reason_codes",List.of("TOPIC_RELATED"),"reason","已认证且保证有空")));}
            };
            try(var runner=new RecommendationModelRunner(untrusted,RecommendationModelRunner.Limits.defaults(),System::currentTimeMillis)) {
                var projection=RecommendationDecisionHost.project(ruleResult,runner.run(modelInput,ruleResult));
                check(projection.size()==1&&!projection.get(0).containsKey("topicAdvice")&&"PENDING".equals(projection.get(0).get("decision")),"untrusted provider cannot alter final host projection");
            }
            try(var runner=new RecommendationModelRunner(new RecommendationModelProvider.MockProvider(),RecommendationModelRunner.Limits.defaults(),System::currentTimeMillis)) {
                var projection=RecommendationDecisionHost.project(ruleResult,runner.run(modelInput,ruleResult));
                check(projection.get(0).containsKey("topicAdvice")&&projection.get(0).get("missingInfo").equals(ruleResult.get(0).value().get("missingInfo")),"valid advisory cannot delete host missing information");
            }
            Map<String,Object> result=RecommendationDecisionHost.analyze(actor,body);String id=(String)result.get("analysisId");
            check(rows(result).size()==7,"only current explicit bound pool");
            check("DISCUSSION_DRAFT".equals(result.get("rulePolicyStatus")),"draft rule policy visible");
            check(Json.write(result.get("model")).contains("RULES")&&!Json.write(result.get("model")).contains("MOCK"),"ordinary host always rules");
            check(!Json.write(result).contains("REVIEW_PHONE_10")&&!Json.write(result).contains("synthetic@example.test")&&!Json.write(result).contains("secret-old-topic"),"host never exposes contacts or superseded extracted text");
            check(rows(result).stream().filter(v->v.get("candidateId").equals("teacher:101")).allMatch(v->v.get("decision").equals("PENDING")),"missing cert pending");
            check(rows(result).stream().filter(v->v.get("candidateId").equals("teacher:103")).allMatch(v->v.get("decision").equals("EXCLUDED")),"revoked cert known exclusion");
            check(Boolean.FALSE.equals(RecommendationDecisionHost.select(actor,id,"teacher:104").get("assignmentCreated")),"manual shortlist does not dispatch");
            rejects(409,()->RecommendationDecisionHost.select(actor,id,"teacher:103"),"trial excluded candidate not selected");
            rejects(404,()->RecommendationDecisionHost.read(colleague,id),"same org other session cannot reuse receipt");
            rejects(403,()->RecommendationDecisionHost.analyze(other,body),"cross org scope denied");
            rows(result).get(0).put("name","MUTATED_CLIENT_COPY");check(!Json.write(RecommendationDecisionHost.read(actor,id)).contains("MUTATED_CLIENT_COPY"),"client map cannot mutate stored receipt");
            Db.exec("UPDATE teacher_resumes SET manual_profile='沟通修改后资料' WHERE id=1");rejects(409,()->RecommendationDecisionHost.read(actor,id),"profile change invalidates receipt");
            Map<String,Object> current=RecommendationDecisionHost.analyze(actor,body);String changed=(String)current.get("analysisId");
            Db.exec("INSERT INTO dispatches VALUES(1,1,104,'合成', '2026-11-01','10:00','11:00','已确认')");rejects(409,()->RecommendationDecisionHost.select(actor,changed,"teacher:104"),"changed known schedule invalidates choice");
            check(rows(RecommendationDecisionHost.analyze(actor,body)).stream().filter(v->v.get("candidateId").equals("teacher:104")).allMatch(v->v.get("decision").equals("EXCLUDED")),"fresh known overlap excludes");
            Db.exec("DELETE FROM dispatches");
            Db.get().setAutoCommit(false);try{rejects(409,()->RecommendationDecisionHost.analyze(actor,body),"uncommitted snapshot rejected");}finally{Db.get().rollback();Db.get().setAutoCommit(true);}
            Db.exec("UPDATE teacher_resumes SET manual_profile=? WHERE id=1","沟通".repeat(131073));rejects(413,()->RecommendationDecisionHost.analyze(actor,body),"whole capture character budget");Db.exec("UPDATE teacher_resumes SET manual_profile='沟通合成资料' WHERE id=1");
            Db.exec("INSERT INTO dispatches SELECT X,1,104,'合成','2026-11-01','10:00','11:00','已确认' FROM SYSTEM_RANGE(1,5001)");rejects(413,()->RecommendationDecisionHost.analyze(actor,body),"whole capture row budget");Db.exec("DELETE FROM dispatches");
            Map<String,Object> manual=new LinkedHashMap<>(body);manual.put("course_code","");check(rows(RecommendationDecisionHost.analyze(actor,manual)).stream().allMatch(v->v.get("decision").equals("PENDING")),"manual course qualification stays pending");
            String revoked=(String)RecommendationDecisionHost.analyze(actor,body).get("analysisId");Auth.revokeUserSessions(actor.uid);rejects(401,()->RecommendationDecisionHost.read(actor,revoked),"revoked session cannot read");rejects(401,()->RecommendationDecisionHost.select(actor,revoked,"teacher:104"),"revoked session cannot select");
            System.out.println("M04RecommendationDecisionHostTest: "+checks+" checks passed; real Auth/M01/M04 on isolated synthetic H2, no server/network.");
        } finally {Db.exec("SHUTDOWN");}
    }
}
