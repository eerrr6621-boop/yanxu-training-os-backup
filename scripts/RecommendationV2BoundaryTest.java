package com.training;
import java.nio.file.*;
import java.util.*;
import static com.training.RecommendationV2Fixture.*;
import static com.training.RecommendationDecision.map;
import static com.training.OrganizationAccess.*;
/** Narrow final review boundaries, independent fresh synthetic H2; never opens a listener. */
public final class RecommendationV2BoundaryTest {
    static int checks;
    static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);System.out.println("PASS "+label);}
    public static void main(String[] args)throws Exception {
        setup(Path.of(args[0]),"SYNTHETIC-V2-BOUNDARIES");
        try {
            var online=input(0);online.put("mode","ONLINE");
            RecommendationDecisionHost.assessmentV2(actors[0],map("input",online,"teacher_id",teachers.get(5),"expected_revision",0,"request_id","mixed-online-fact","facts",facts(0,5),"reason","合成双形式核对"),true);
            long mixed=dispatch(0,5,"09:00","11:00");var p=DispatchConfirmationGuard.preview(actors[0],mixed);
            ok(Boolean.FALSE.equals(p.get("buffer_verified"))&&Json.write(p).contains("形式不一致"),"mixed ONLINE/ONSITE cannot prove travel buffers");
            ok(Boolean.TRUE.equals(p.get("can_confirm")),"uncertain form preserves original manual final decision with warnings");
            long unknownTeacher=teachers.get(20);Db.exec("INSERT INTO dispatches(project_id,teacher_id,subject,teach_date,status) VALUES(?,?,'同日缺时段','2026-11-10','已确认')",projects[1],unknownTeacher);
            long current=dispatch(0,20,"15:00","16:00");
            ok(Boolean.FALSE.equals(DispatchConfirmationGuard.preview(actors[0],current).get("can_confirm")),"same-day incomplete confirmed occupation is not empty availability");
            long unrelated=dispatch(0,0,"15:00","16:00");Db.exec("UPDATE dispatches SET teach_date='2026-11-12',subject='原手工主题',venue='独立教室' WHERE id=?",unrelated);
            Configuration c=OrganizationAccessStore.configuration();var grants=c.grants().stream().filter(g->!g.ruleId().equals("catalog-FILLER")).toList();
            OrganizationAccessStore.publish(admin,c.version(),new Configuration("synthetic-v2-no-catalog",c.codeRules(),c.roleCodes(),c.organizations(),c.people(),c.relations(),c.accountBindings(),grants,c.combinedApprovals()));sessions();
            var manual=DispatchConfirmationGuard.preview(actors[0],unrelated);
            ok(Boolean.TRUE.equals(manual.get("can_confirm")),"unrelated previous assessment cannot impose catalog permission on manual course");
            ok(Json.write(manual).contains("手工课程资格待核"),"manual exception never certifies qualification");
            try {DispatchConfirmationGuard.preview(actors[0],mixed);throw new AssertionError("related assessment must reauthorize catalog");}catch(Api.ApiException e){ok(e.code==403,"related fact consumption still rechecks existing catalog permission");}
            System.out.println("RecommendationV2BoundaryTest: "+checks+" checks passed; no listener.");
        } finally {Db.exec("SHUTDOWN");}
    }
}
