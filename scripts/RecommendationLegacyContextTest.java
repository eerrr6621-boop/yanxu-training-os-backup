package com.training;

import java.nio.file.Path;
import java.util.*;
import static com.training.RecommendationDecision.map;
import static com.training.RecommendationV2Fixture.*;

/** Fresh synthetic database only. Registration and recommendation share real permission/source boundaries. */
public final class RecommendationLegacyContextTest {
    static int checks;
    interface Work {void run()throws Exception;}
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    static void rejects(Work action)throws Exception {try{action.run();throw new AssertionError("expected source/access rejection");}catch(Api.ApiException error){check(Set.of(403,404,409).contains(error.code),"denied without provenance bypass");}}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Object value){return (List<Map<String,Object>>)value;}
    static Map<String,Object> candidate(Map<String,Object> result,long teacher){return rows(rows(result.get("results")).get(0).get("candidates")).stream().filter(c->n(c.get("teacherId"))==teacher).findFirst().orElseThrow();}
    public static void main(String[] args)throws Exception {
        setup(Path.of(args[0]),"SYNTHETIC-V2-LEGACY-ONLY");
        try {
            long demand=Db.insert("INSERT INTO demands(title,unit,status) VALUES('合成历史需求','合成历史单位','已受理')");
            long project=Db.insert("INSERT INTO projects(demand_id,title,unit,status) VALUES(?,'合成历史项目','合成历史单位','进行中')",demand);
            Map<String,Object> input=input(0);input.put("context_id",project);
            rejects(()->RecommendationDecisionHost.analyzeV2(actors[0],List.of(input)));
            Map<String,Object> preview=LegacyOwnershipIntegration.preview(admin,map("type","PROJECT","id",project,"organization_code","001"));
            LegacyOwnershipIntegration.register(admin,map("type","PROJECT","id",project,"organization_code","001","expected_version",0,"source_version",preview.get("source_version"),"reason","合成已核实来源","request_id","recommendation-legacy-context-0001"));
            check(Db.one("SELECT project_id FROM workflow_acceptances WHERE project_id=?",project)==null,"explicit registration never fabricates workflow acceptance");
            List<Map<String,Object>> contexts=rows(RecommendationDecisionHost.contextsV2(actors[0],scopes[0]).get("contexts"));
            check(contexts.stream().anyMatch(c->"PROJECT".equals(c.get("type"))&&n(c.get("id"))==project&&Boolean.TRUE.equals(c.get("legacy_registered"))),"registered historical project is selectable");
            check(contexts.stream().anyMatch(c->"DEMAND".equals(c.get("type"))&&n(c.get("id"))==demand),"registered related demand is selectable");
            rejects(()->RecommendationDecisionHost.analyzeV2(actors[1],List.of(input)));
            Map<String,Object> before=RecommendationDecisionHost.analyzeV2(actors[0],List.of(input));
            check(Boolean.TRUE.equals(candidate(before,teachers.get(0)).get("canRecordAssessment")),"authorized owner may record facts for registered history");
            Map<String,Object> saved=RecommendationDecisionHost.assessmentV2(actors[0],map("input",input,"teacher_id",teachers.get(0),"expected_revision",0,"request_id","legacy-facts-0001","facts",facts(0,0),"reason","合成明确确认本次条件"),true);
            String assessment=(String)m(saved.get("result")).get("assessment_id");
            Map<String,Object> event=Json.parseMap((String)Db.one("SELECT payload FROM recommendation_assessment_events WHERE assessment_id=?",assessment).get("payload"));
            check("RECOMMENDATION-ASSESSMENT-3".equals(event.get("format"))&&LegacyOwnershipIntegration.source("PROJECT",project).get("source_version").equals(event.get("legacy_source_version")),"new historical facts audit binds registered source version");
            Map<String,Object> ready=RecommendationDecisionHost.analyzeV2(actors[0],List.of(input));
            check("READY_FOR_REVIEW".equals(candidate(ready,teachers.get(0)).get("decision")),"registered history uses qualified fact-backed recommendation without fallback");
            long dispatch=Db.insert("INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,start_time,end_time,venue,hours,status) VALUES(?,?,'亲子 沟通','2026-11-10','09:00','11:00','合成地点0',2,'已发送')",project,teachers.get(0));
            check(RecommendationAssessmentIntegration.forDispatch(actors[0],Db.one("SELECT * FROM dispatches WHERE id=?",dispatch)).size()==1,"registered dispatch resolves exact historical project facts");
            Map<String,Object> demandInput=new LinkedHashMap<>(input);demandInput.put("context_type","DEMAND");demandInput.put("context_id",demand);
            Map<String,Object> demandResult=RecommendationDecisionHost.analyzeV2(actors[0],List.of(demandInput));
            check(((List<?>)candidate(demandResult,teachers.get(0)).get("coordinationNotices")).isEmpty(),"same registered demand project does not create foreign-invitation warning");
            Db.exec("UPDATE legacy_ownership SET digest=? WHERE entity_type='PROJECT' AND entity_id=?","0".repeat(64),project);
            rejects(()->RecommendationDecisionHost.readV2(actors[0],(String)ready.get("analysisId")));
            rejects(()->RecommendationAssessmentIntegration.locate(actors[0],assessment));
            System.out.println("RecommendationLegacyContextTest: "+checks+" checks passed; synthetic only, no listener or CPA.");
        } finally {Db.exec("SHUTDOWN");}
    }
}
