package com.training;

import java.time.*;
import java.util.*;
import static com.training.RecommendationDecision.*;
import static com.training.RecommendationRanking.*;
import static com.training.RecommendationJointPlanner.*;

public final class RecommendationV2JointPlannerTest {
    private static int checks;
    private static void check(boolean value,String label) { if(!value)throw new AssertionError(label);checks++; }
    private static void rejects(Runnable action,String label) { try{action.run();throw new AssertionError(label);}catch(IllegalArgumentException expected){checks++;} }
    private static Window window(String start,String end) { return new Window(Instant.parse(start),Instant.parse(end)); }
    private static Ranked ranked(String request,long id,Window window) { return new Ranked(id,"teacher:"+id,request,State.PASS,Tier.LOCAL_OR_ONLINE,window,WindowBasis.CONFIRMED_TRAVEL_BUFFERS,List.of("PREFERENCE_LOCAL_OR_ONLINE"),List.of()); }
    private static List<Ranked> pool(String request,Window window) {
        List<Input> inputs=new ArrayList<>();
        for(long id=1;id<=179;id++) {
            Candidate c=new Candidate(id,"同名合成教师","在库","合成常驻地",List.of(),Confirmation.CONFIRMED,Confirmation.CONFIRMED,List.of(),List.of());
            Decision decision=new Decision(c,State.PASS,List.of(new Check("COURSE_QUALIFIED",State.PASS,"合成已核资格")),List.of());
            TravelFacts travel=new TravelFacts(id,request,Confirmation.CONFIRMED,TransportMode.LOCAL,"明确合成出发地",request+"-venue",10,20,30,30,null,null);
            inputs.add(new Input(decision,IdentityStatus.CONFIRMED,travel));
        }
        Result result=rank(new Context(request,false,request+"-venue",window),inputs);
        if(result.scanned()!=179 || result.normal().size()!=179)throw new AssertionError("full per-request rank");
        return result.normal();
    }
    private static RecommendationJointPlanner.Request request(String id,Window window,long... ids) {
        List<Ranked> candidates=new ArrayList<>(); for(long teacher:ids)candidates.add(ranked(id,teacher,window));return new RecommendationJointPlanner.Request(id,candidates);
    }
    public static void main(String[] args) {
        Window morning=window("2026-11-01T01:00:00Z","2026-11-01T03:00:00Z");
        List<Ranked> pool=pool("a",morning);
        List<RecommendationJointPlanner.Request> three=List.of(new RecommendationJointPlanner.Request("synthetic-org-a",pool("synthetic-org-a",morning)),new RecommendationJointPlanner.Request("synthetic-org-b",pool("synthetic-org-b",morning)),new RecommendationJointPlanner.Request("synthetic-org-c",pool("synthetic-org-c",morning)));
        Plan full=solve(three,100000);
        check(full.assignedCount()==3 && !full.limited() && full.coverageProof()==CoverageProof.UPPER_BOUND_REACHED,"179 teachers by three requests reaches maximum coverage");
        check(full.choices().stream().map(Choice::teacherId).toList().equals(List.of(1L,2L,3L)),"deterministic compatible suggestions");
        check(full.choices().get(1).reasonCode().equals("JOINT_CONFLICT_ALTERNATIVE"),"generic compatible alternative reason contains no other demand detail");
        check(solve(three,100000).equals(full),"repeat stable without reservation side effect");
        check(three.stream().allMatch(r->r.candidates().size()==179 && r.candidates().get(0).teacherId()==1),"same teacher remains in all independent lists after joint suggestion");
        check(full.ruleVersion().equals(RecommendationJointPlanner.RULE_VERSION),"joint rule version explicit");
        // Teaching intervals 01:00–03:00 and 03:30–05:30 with explicitly confirmed 15-minute buffers each.
        Plan reuse=solve(List.of(request("a",window("2026-11-01T00:45:00Z","2026-11-01T03:15:00Z"),1),request("b",window("2026-11-01T03:15:00Z","2026-11-01T05:45:00Z"),1)),1000);
        check(reuse.assignedCount()==2,"same teacher allowed only at touching explicitly buffered windows");
        Plan overlap=solve(List.of(request("a",morning,1),request("b",window("2026-11-01T02:59:00Z","2026-11-01T05:00:00Z"),1)),1000);
        check(overlap.assignedCount()==1 && overlap.choices().get(1).teacherId()==null,"same identity cannot overlap");
        Plan distinct=solve(List.of(request("same-name-a",morning,1),request("same-name-b",morning,2)),1000);
        check(distinct.assignedCount()==2,"different stable IDs are not collapsed by same-name context");
        Window beforeExtended=window("2026-10-31T23:00:00Z","2026-11-01T04:00:00Z"),afterExtended=window("2026-11-01T03:30:00Z","2026-11-01T07:00:00Z");
        Plan buffers=solve(List.of(request("a",beforeExtended,1),request("b",afterExtended,1)),1000);
        check(buffers.assignedCount()==1,"known travel buffers conflict although teaching windows could differ");
        Plan backtrack=solve(List.of(request("a",morning,1,2,3,4,5),request("b",morning,1),request("c",morning,2)),10000);
        check(backtrack.assignedCount()==3 && backtrack.choices().get(0).teacherId()==3L,"backtracking leaves scarce people for other requests");
        check(backtrack.choices().get(0).reasonCode().equals("JOINT_CONFLICT_ALTERNATIVE"),"backtracked alternative explicitly explained");
        List<RecommendationJointPlanner.Request> noThird=List.of(new RecommendationJointPlanner.Request("a",pool),request("b",morning,1),request("c",morning));
        Plan fullPool=solve(noThird,100000);
        check(fullPool.assignedCount()==2 && !fullPool.limited() && fullPool.coverageProof()==CoverageProof.EXHAUSTED,"shortage allows unassigned without pretending full allocation");
        check(fullPool.visitedNodes()>3 && noThird.get(0).candidates().size()==179,"planner keeps and examines full pool beyond top three");
        check(fullPool.choices().get(2).reasonCode().equals("NO_NORMAL_CANDIDATE"),"empty normal pool explicitly unassigned");
        Plan budget=solve(three,1);
        check(budget.limited() && budget.visitedNodes()==1 && budget.coverageProof()==CoverageProof.BUDGET_LIMITED,"budget exhaustion explicit not global optimum");
        check(budget.assignedCount()==1 && budget.choices().get(1).teacherId()==null,"best feasible partial suggestion retained when limited");
        check(budget.choices().get(1).reasonCode().equals("UNASSIGNED_SEARCH_LIMIT"),"limited unassigned explained");
        Plan empty=solve(List.of(request("a",morning),request("b",morning),request("c",morning)),1000);
        check(empty.assignedCount()==0 && !empty.limited(),"zero normal candidates valid no forced teachers");
        rejects(()->solve(List.of(),10),"empty request collection invalid");
        rejects(()->solve(List.of(request("a",morning),request("b",morning),request("c",morning),request("d",morning)),10),"at most three demands");
        rejects(()->solve(List.of(request("a",morning),request("a",morning)),10),"duplicate demand IDs invalid");
        rejects(()->solve(three,0),"zero budget invalid");
        rejects(()->request("a",morning,1,1),"duplicate candidate identity rejected");
        rejects(()->new RecommendationJointPlanner.Request("a",List.of(new Ranked(1,"teacher:1","a",State.PENDING,Tier.UNCONFIRMED,null,WindowBasis.UNKNOWN,List.of(),List.of("UNKNOWN")))),"pending cannot enter normal joint pool");
        rejects(()->new Ranked(1,"teacher:2","a",State.PASS,Tier.LOCAL_OR_ONLINE,morning,WindowBasis.CONFIRMED_TRAVEL_BUFFERS,List.of(),List.of()),"candidate key mismatch rejected");
        rejects(()->new Ranked(1,"teacher:1","a",State.PASS,Tier.LOCAL_OR_ONLINE,null,WindowBasis.CONFIRMED_TRAVEL_BUFFERS,List.of(),List.of()),"normal without complete occupied window rejected");
        rejects(()->new RecommendationJointPlanner.Request("b",List.of(ranked("a",1,morning))),"other request ranked facts cannot enter this request");
        rejects(()->new Ranked(1,"teacher:1","a",State.PASS,Tier.LOCAL_OR_ONLINE,morning,WindowBasis.UNKNOWN,List.of(),List.of()),"unknown journey basis cannot claim normal combined window");
        check(!full.toString().contains("evidence") && !full.toString().contains("sourceRef") && !full.toString().contains("topic"),"joint output has no other demand content/evidence");
        System.out.println("RecommendationV2JointPlannerTest: "+checks+" checks passed; 179 teachers x 3 synthetic requests, no writes/locks/service.");
    }
}
