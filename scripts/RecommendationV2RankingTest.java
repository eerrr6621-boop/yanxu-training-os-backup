package com.training;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.training.RecommendationDecision.*;
import static com.training.RecommendationRanking.*;

public final class RecommendationV2RankingTest {
    private static int checks;
    private static final Window WINDOW=new Window(Instant.parse("2026-11-01T01:00:00Z"),Instant.parse("2026-11-01T03:00:00Z"));
    private static final Context CONTEXT=new Context("synthetic-request-a",false,"synthetic-venue",WINDOW);
    private static void check(boolean value,String label) { if(!value)throw new AssertionError(label);checks++; }
    private static void rejects(Runnable action,String label) { try{action.run();throw new AssertionError(label);}catch(IllegalArgumentException expected){checks++;} }
    private static Cost cost(String value) { return new Cost(new BigDecimal(value),"CNY","TRAVEL_ONLY"); }
    private static Decision decision(long id,List<String> qualifications,Confirmation service,Confirmation availability,List<Booking> bookings,boolean topic) {
        List<Evidence> evidence=topic?List.of(evidence("SYNTHETIC",id,"PROFILE_TOPIC","虚构主题资料","synthetic:"+id,"1","沟通 沟通 沟通")):List.of();
        Candidate candidate=new Candidate(id,"同名虚构教师","在库","synthetic-city",qualifications,service,availability,bookings,evidence);
        var request=new RecommendationDecision.Request("沟通","SYNTHETIC-COURSE","2026-11-01","09:00","11:00","ONSITE",List.of());
        return decide(new Snapshot("SYNTHETIC","a".repeat(64),request,List.of(candidate),true)).get(0);
    }
    private static Decision decision(long id) { return decision(id,List.of(),Confirmation.CONFIRMED,Confirmation.CONFIRMED,List.of(),false); }
    private static TravelFacts travel(long id,TransportMode mode,Integer leg,Integer door,Integer before,Integer after,Long distance,Cost cost) {
        return new TravelFacts(id,CONTEXT.requestId(),Confirmation.CONFIRMED,mode,"explicit-synthetic-origin",CONTEXT.teachingPlace(),leg,door,before,after,distance,cost);
    }
    private static Input input(long id,TransportMode mode,int leg,Long distance,Cost cost) { return new Input(decision(id),IdentityStatus.CONFIRMED,travel(id,mode,leg,leg+30,60,30,distance,cost)); }
    private static Ranked only(Input input) { Result r=rank(CONTEXT,List.of(input));return !r.normal().isEmpty()?r.normal().get(0):!r.pending().isEmpty()?r.pending().get(0):r.excluded().get(0); }
    private static List<Long> ids(List<Ranked> list) { return list.stream().map(Ranked::teacherId).toList(); }
    public static void main(String[] args) {
        List<Input> people=new ArrayList<>();
        for(long id=1;id<=179;id++) people.add(input(id,id<=60?TransportMode.LOCAL:id<=120?TransportMode.RAIL:id<=160?TransportMode.AIR:TransportMode.OTHER,id<=60?20:id<=120?180:id<=160?150:240,null,null));
        Collections.shuffle(people,new Random(179));Result full=rank(CONTEXT,people);
        check(full.scanned()==179 && full.normal().size()==179 && full.pending().isEmpty() && full.excluded().isEmpty(),"all 179 inspected without history penalty");
        check(ids(full.topThree()).equals(List.of(1L,2L,3L)) && full.shortage()==0,"local top three only");
        check(full.explanationShortlist().equals(List.of(1L,2L,3L,4L,5L,6L)),"single bounded explanation shortlist does not cap full pool");
        Collections.reverse(people);check(ids(rank(CONTEXT,people).normal()).equals(ids(full.normal())),"stable order independent of input iteration");
        check(full.ruleVersion().equals(RULE_VERSION),"rule version explicit");
        check(full.normal().get(0).missingInfo().containsAll(List.of("DISTANCE_UNKNOWN","COST_UNKNOWN")),"unknown metrics explicit, not zero or lowest cost");
        check(only(input(1,TransportMode.RAIL,180,null,null)).tier()==Tier.RAIL_WITHIN_180,"rail boundary 180 preferred");
        check(only(input(1,TransportMode.RAIL,181,null,null)).state()==State.PASS && only(input(1,TransportMode.RAIL,181,null,null)).tier()==Tier.OTHER_FEASIBLE,"rail over threshold remains feasible candidate");
        check(only(input(1,TransportMode.AIR,150,null,null)).tier()==Tier.AIR_WITHIN_150,"air boundary 150 preferred");
        check(only(input(1,TransportMode.AIR,151,null,null)).state()==State.PASS && only(input(1,TransportMode.AIR,151,null,null)).tier()==Tier.OTHER_FEASIBLE,"air over threshold remains candidate");
        Input farSemantic=new Input(decision(1,List.of(),Confirmation.CONFIRMED,Confirmation.CONFIRMED,List.of(),true),IdentityStatus.CONFIRMED,travel(1,TransportMode.AIR,120,180,180,180,100000L,cost("1")));
        Input localSparse=input(99,TransportMode.LOCAL,20,5000L,cost("999")),railSparse=input(98,TransportMode.RAIL,120,50000L,cost("999"));
        check(ids(rank(CONTEXT,List.of(farSemantic,localSparse,railSparse)).normal()).equals(List.of(99L,98L,1L)),"strong semantic far candidate cannot outrank local or preferred rail");
        check(rank(CONTEXT,List.of(input(1,TransportMode.LOCAL,10,null,null),input(2,TransportMode.LOCAL,10,null,null))).normal().size()==2,"same name different IDs remain distinct");
        rejects(()->rank(CONTEXT,List.of(input(1,TransportMode.LOCAL,10,null,null),input(1,TransportMode.LOCAL,10,null,null))),"duplicate stable teacher ID rejected");
        check(only(new Input(decision(1),IdentityStatus.UNKNOWN,travel(1,TransportMode.LOCAL,10,20,0,0,null,null))).state()==State.PENDING,"ambiguous identity pending");
        check(only(new Input(decision(1),IdentityStatus.INVALID,travel(1,TransportMode.LOCAL,10,20,0,0,null,null))).state()==State.EXCLUDED,"confirmed invalid identity excluded");
        rejects(()->only(new Input(decision(1),IdentityStatus.CONFIRMED,travel(2,TransportMode.LOCAL,10,20,0,0,null,null))),"other teacher facts rejected");
        rejects(()->only(new Input(decision(1),IdentityStatus.CONFIRMED,new TravelFacts(1,"other-request",Confirmation.CONFIRMED,TransportMode.LOCAL,"origin","synthetic-venue",10,20,0,0,null,null))),"other request facts rejected");
        rejects(()->only(new Input(decision(1),IdentityStatus.CONFIRMED,new TravelFacts(1,CONTEXT.requestId(),Confirmation.CONFIRMED,TransportMode.LOCAL,"origin","other-venue",10,20,0,0,null,null))),"other destination facts rejected");
        Input unknownQualification=new Input(decision(1,List.of("CERTIFICATION_MISSING"),Confirmation.CONFIRMED,Confirmation.CONFIRMED,List.of(),false),IdentityStatus.CONFIRMED,travel(1,TransportMode.LOCAL,10,20,0,0,null,null));
        check(only(unknownQualification).state()==State.PENDING,"missing qualification is pending, not excluded");
        for(String code:List.of("NOT_CERTIFIED","CERTIFICATION_REVOKED","NOT_YET_VALID","EXPIRED")) check(only(new Input(decision(1,List.of(code),Confirmation.CONFIRMED,Confirmation.CONFIRMED,List.of(),false),IdentityStatus.CONFIRMED,travel(1,TransportMode.LOCAL,10,20,0,0,null,null))).state()==State.EXCLUDED,"explicit qualification failure never normal "+code);
        Decision negative=decision(1,List.of("NOT_CERTIFIED"),Confirmation.CONFIRMED,Confirmation.CONFIRMED,List.of(),false);
        check(only(new Input(new Decision(negative.candidate(),State.PASS,List.of(),List.of()),IdentityStatus.CONFIRMED,travel(1,TransportMode.LOCAL,10,20,0,0,null,null))).state()==State.EXCLUDED,"contradictory PASS does not erase explicit negative qualification");
        check(only(new Input(decision(1,List.of(),Confirmation.CONFIRMED,Confirmation.CONFIRMED,List.of(new Booking("2026-11-01","10:00","12:00","已确认")),false),IdentityStatus.CONFIRMED,travel(1,TransportMode.LOCAL,10,20,0,0,null,null))).state()==State.EXCLUDED,"confirmed conflict excluded despite local travel");
        check(only(new Input(decision(1,List.of(),Confirmation.UNKNOWN,Confirmation.CONFIRMED,List.of(),false),IdentityStatus.CONFIRMED,null)).state()==State.PENDING,"unknown service not invented");
        check(only(new Input(decision(1,List.of(),Confirmation.CONFIRMED,Confirmation.UNKNOWN,List.of(),false),IdentityStatus.CONFIRMED,travel(1,TransportMode.LOCAL,10,20,0,0,null,null))).state()==State.PENDING,"no bookings does not imply complete availability");
        check(only(new Input(decision(1),IdentityStatus.CONFIRMED,null)).state()==State.PENDING,"travel unknown separated");
        check(only(new Input(decision(1),IdentityStatus.CONFIRMED,new TravelFacts(1,CONTEXT.requestId(),Confirmation.CONFIRMED,TransportMode.LOCAL,"",CONTEXT.teachingPlace(),10,20,0,0,null,null))).state()==State.PENDING,"local base city not actual departure");
        check(only(new Input(decision(1),IdentityStatus.CONFIRMED,travel(1,TransportMode.RAIL,120,null,0,0,null,null))).state()==State.PENDING,"rail leg cannot replace door-to-door fact");
        check(only(new Input(decision(1),IdentityStatus.CONFIRMED,travel(1,TransportMode.LOCAL,10,20,null,0,null,null))).state()==State.PENDING,"unknown buffer not zero");
        check(only(new Input(decision(1),IdentityStatus.CONFIRMED,new TravelFacts(1,CONTEXT.requestId(),Confirmation.UNKNOWN,TransportMode.LOCAL,"origin",CONTEXT.teachingPlace(),10,20,0,0,null,null))).state()==State.PENDING,"estimated duration not confirmed feasibility");
        check(only(new Input(decision(1),IdentityStatus.CONFIRMED,new TravelFacts(1,CONTEXT.requestId(),Confirmation.DECLINED,TransportMode.LOCAL,"origin",CONTEXT.teachingPlace(),10,20,0,0,null,null))).state()==State.EXCLUDED,"explicit cannot reach excluded");
        check(only(new Input(decision(1),IdentityStatus.CONFIRMED,travel(1,TransportMode.UNKNOWN,10,20,0,0,null,null))).state()==State.PENDING,"unknown mode pending");
        Ranked unknownLeg=only(new Input(decision(1),IdentityStatus.CONFIRMED,travel(1,TransportMode.RAIL,null,200,200,200,null,null)));
        check(unknownLeg.state()==State.PASS && unknownLeg.tier()==Tier.OTHER_FEASIBLE && unknownLeg.missingInfo().contains("PREFERENCE_LEG_DURATION_UNKNOWN"),"confirmed complete journey with unknown preference leg not excluded");
        Result metric=rank(CONTEXT,List.of(input(1,TransportMode.LOCAL,10,200L,cost("20")),input(2,TransportMode.LOCAL,10,100L,cost("10"))));
        check(ids(metric.normal()).equals(List.of(2L,1L)),"dominating known comparable distance and cost preferred");
        check(ids(rank(CONTEXT,List.of(input(1,TransportMode.LOCAL,10,100L,cost("20")),input(2,TransportMode.LOCAL,10,200L,cost("10")))).normal()).equals(List.of(1L,2L)),"distance cost tradeoff has no invented weight");
        check(ids(rank(CONTEXT,List.of(input(1,TransportMode.LOCAL,10,null,null),input(2,TransportMode.LOCAL,10,null,cost("1")))).normal()).equals(List.of(2L,1L)),"known metric precedes wholly unknown facts without converting unknown to zero");
        check(ids(rank(CONTEXT,List.of(input(1,TransportMode.LOCAL,10,null,cost("20")),input(2,TransportMode.LOCAL,10,null,cost("10")))).normal()).equals(List.of(2L,1L)),"comparable known costs alone useful");
        check(ids(rank(CONTEXT,List.of(input(1,TransportMode.LOCAL,10,null,new Cost(new BigDecimal("20"),"USD","TRAVEL_ONLY")),input(2,TransportMode.LOCAL,10,null,cost("1")))).normal()).equals(List.of(1L,2L)),"different currency not directly compared");
        check(ids(rank(CONTEXT,List.of(input(1,TransportMode.LOCAL,10,null,new Cost(new BigDecimal("20"),"CNY","FEE_ONLY")),input(2,TransportMode.LOCAL,10,null,cost("1")))).normal()).equals(List.of(1L,2L)),"different cost coverage not total cost comparison");
        List<Input> incomplete=List.of(input(1,TransportMode.RAIL,120,null,null),input(2,TransportMode.RAIL,120,50000L,cost("80")),input(3,TransportMode.RAIL,120,900000L,cost("600")),input(4,TransportMode.RAIL,120,10000L,null));
        check(ids(rank(CONTEXT,incomplete).normal()).equals(List.of(2L,3L,4L,1L)),"within one rail tier complete facts precede partial then unknown");
        check(ids(rank(CONTEXT,List.of(input(5,TransportMode.LOCAL,10,null,cost("0")),input(9,TransportMode.LOCAL,10,3000L,cost("20")))).normal()).equals(List.of(9L,5L)),"known zero is not unknown but cannot override missing distance completeness");
        List<Input> shuffled=new ArrayList<>(incomplete);for(int seed=0;seed<12;seed++){Collections.shuffle(shuffled,new Random(seed));check(ids(rank(CONTEXT,shuffled).normal()).equals(List.of(2L,3L,4L,1L)),"completeness DAG is deterministic after shuffle "+seed);}
        Ranked buffered=only(new Input(decision(1),IdentityStatus.CONFIRMED,travel(1,TransportMode.LOCAL,10,20,90,60,null,null)));
        check(buffered.occupiedWindow().start().equals(Instant.parse("2026-10-31T23:30:00Z")) && buffered.occupiedWindow().end().equals(Instant.parse("2026-11-01T04:00:00Z")),"buffer extends complete instant window across dates");
        check(rank(new Context(CONTEXT.requestId(),false,CONTEXT.teachingPlace(),null),List.of(input(1,TransportMode.LOCAL,10,null,null))).pending().size()==1,"unknown teaching window not normal");
        check(rank(new Context(CONTEXT.requestId(),true,"",WINDOW),List.of(new Input(decision(1),IdentityStatus.CONFIRMED,null))).normal().size()==1,"online requires no fabricated journey");
        Result small=rank(CONTEXT,List.of(input(3,TransportMode.LOCAL,10,null,null),input(2,TransportMode.LOCAL,10,null,null),unknownQualification));
        check(small.topThree().size()==2 && small.shortage()==1 && small.pending().size()==1,"shortage not filled by pending candidate");
        check(small.explanationShortlist().equals(List.of(2L,3L,1L)),"bounded advice can include pending without changing normal list");
        check(!full.toString().contains("同名") && !full.toString().contains("虚构主题资料"),"algorithm result contains no names or evidence body");
        rejects(()->new Cost(BigDecimal.ONE,"CNY",null),"missing cost coverage rejected");
        rejects(()->travel(1,TransportMode.RAIL,120,100,0,0,null,null),"contradictory leg vs journey rejected");
        System.out.println("RecommendationV2RankingTest: "+checks+" checks passed; 179 synthetic teachers, no database/service/model.");
    }
}
