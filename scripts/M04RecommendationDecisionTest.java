package com.training;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.training.RecommendationDecision.*;

/** Pure synthetic checks: no database, server, filesystem fixture or external provider. */
public final class M04RecommendationDecisionTest {
    private static int checks;
    private static final Request STANDARD=new Request("沟通","C1","2026-11-01","09:00","12:00","ONLINE",List.of());
    private static void check(boolean value,String label) { checks++;if(!value)throw new AssertionError(label); }
    private static void invalid(Runnable work,String label) { try {work.run();throw new AssertionError(label);}catch(IllegalArgumentException expected){checks++;} }
    private static Candidate candidate(long id,List<String> codes,List<Booking> bookings,List<Evidence> evidence) {
        return new Candidate(id,"同名候选","在库","合成甲城",codes,Confirmation.CONFIRMED,Confirmation.CONFIRMED,bookings,evidence);
    }
    private static Snapshot snapshot(Request r,List<Candidate> candidates,boolean synthetic) {return new Snapshot("SYNTHETIC_ORG",hash("snapshot"),r,candidates,synthetic);}
    private static Decision decision(Request r,Candidate c){return decide(snapshot(r,List.of(c),true)).get(0);}
    private interface Inference { Map<String,Object> run(Map<String,Object> value)throws Exception; }
    private static RecommendationModelProvider provider(Inference inference) {return new RecommendationModelProvider(){ public String id(){return "MOCK";}public boolean modelVisible(){return true;}public boolean syntheticOnly(){return true;}public Map<String,Object> infer(Map<String,Object> v)throws Exception{return inference.run(v);}};}
    private static RecommendationModelRunner runner(RecommendationModelProvider provider,int ms,int retries,int calls,int output) {return new RecommendationModelRunner(provider,new RecommendationModelRunner.Limits(ms,retries,1,262144,output,calls,1048576),System::currentTimeMillis);}
    private static void status(RecommendationModelRunner r,Snapshot s,String status) {check(status.equals(r.run(s,decide(s)).metadata().get("status")),status);}
    public static void main(String[] ignored)throws Exception {
        Candidate clear=candidate(2,List.of(),List.of(),List.of());
        check(decision(STANDARD,clear).state()==State.PASS,"no historical/topic record cannot lower known qualification/availability");
        check(!((List<?>)decision(STANDARD,clear).value().get("topicReview")).isEmpty(),"topic review remains advisory");
        for(String code:List.of("CERTIFICATION_MISSING","CERTIFICATION_UNKNOWN","LEVEL_UNKNOWN","CITY_UNKNOWN")) check(decision(STANDARD,candidate(1,List.of(code),List.of(),List.of())).state()==State.PENDING,code);
        for(String code:List.of("NOT_CERTIFIED","CERTIFICATION_REVOKED","NOT_YET_VALID","EXPIRED")) check(decision(STANDARD,candidate(1,List.of(code),List.of(),List.of())).state()==State.EXCLUDED,code);
        check(decision(new Request("主题","","","","","UNKNOWN",List.of()),clear).state()==State.PENDING,"manual topic and unknown conditions stay pending");
        check(decision(new Request("主题","C1","2026-11-01","09:00","12:00","ONLINE",List.of("合成乙城")),clear).state()==State.EXCLUDED,"explicit city limit also applies online");
        for(String date:List.of("2026-02-30","2026-13-01","0000-01-01")) invalid(()->new Request("主题","",date,"","","ONLINE",List.of()),"bad calendar date "+date);
        invalid(()->new Request("主题","","2026-11-01","12:00","09:00","ONLINE",List.of()),"reversed time");
        invalid(()->new Request("主题","","","09:00","12:00","ONLINE",List.of()),"time without date");
        for(Booking b:List.of(new Booking("2026-11-01","","","已确认"),new Booking("2026-99-01","09:00","10:00","已确认"),new Booking("2026-11-01","09:00","10:00","待发送"))) check(decision(STANDARD,candidate(1,List.of(),List.of(b),List.of())).state()==State.PENDING,"invalid/tentative booking not confirmed conflict");
        check(decision(STANDARD,candidate(1,List.of(),List.of(new Booking("2026-11-01","11:00","13:00","已确认")),List.of())).state()==State.EXCLUDED,"known interval overlap excluded");
        check(decision(STANDARD,candidate(1,List.of(),List.of(new Booking("2026-11-01","12:00","13:00","已确认")),List.of())).state()==State.PASS,"touching boundary not overlap");
        Evidence one=evidence("SYNTHETIC_ORG",1,"PROFILE_TOPIC","人工资料","resume:1","v1","合成沟通主题"), two=evidence("SYNTHETIC_ORG",2,"HISTORY_TOPIC","历史主题","dispatch:2","v1","沟通");
        Candidate first=candidate(1,List.of(),List.of(),List.of(one)),second=candidate(2,List.of("CERTIFICATION_MISSING"),List.of(),List.of(two));
        Request multi=new Request("亲子 沟通","C1","2026-11-01","09:00","12:00","ONLINE",List.of());
        Decision partial=decision(multi,first);
        Map<String,Object> partialMock=new RecommendationModelProvider.MockProvider().infer(map("topic",multi.topic(),"candidates",List.of(map("candidate_id",first.id(),"evidence",List.of(map("evidence_id",one.id(),"kind",one.kind(),"text",one.text()))))));
        check(partial.state()==State.PASS && partial.matchedEvidenceIds().equals(List.of(one.id())) && ((List<?>)partial.value().get("topicReview")).isEmpty() && Json.write(partialMock).contains("TOPIC_RELATED"),"multi-term rule/mock clue shares exact term semantics without changing qualification");
        Snapshot s=snapshot(STANDARD,List.of(second,first),true);
        check(decide(s).get(0).candidate().teacherId()==1,"stable ID order not history count");
        invalid(()->candidate(1,List.of(),List.of(),List.of(two)),"foreign evidence rejected at capture");
        try(var r=new RecommendationModelRunner()){status(r,s,"DISABLED");}
        try(var r=runner(new RecommendationModelProvider.MockProvider(),1000,1,10,131072)) {
            var result=r.run(s,decide(s));check("MOCK".equals(result.metadata().get("source")),"mock label");check(Boolean.FALSE.equals(result.metadata().get("realInference")),"not real inference");
            check(result.suggestions().size()==2,"all candidates including pending preserved");check(decide(s).get(1).state()==State.PENDING,"AI cannot qualify pending");
            status(r,snapshot(STANDARD,List.of(first),false),"SYNTHETIC_ONLY");
        }
        Map<String,Map<String,Evidence>> allowed=new LinkedHashMap<>();allowed.put("teacher:1",Map.of(one.id(),one));allowed.put("teacher:2",Map.of(two.id(),two));
        Map<String,Object> row1=map("candidate_id","teacher:1","evidence_ids",List.of(one.id()),"reason_codes",List.of("TOPIC_RELATED"));
        Map<String,Object> row2=map("candidate_id","teacher:2","evidence_ids",List.of(),"reason_codes",List.of("TOPIC_UNCLEAR"));
        check(RecommendationModelRunner.validate(map("suggestions",List.of(row1,row2)),allowed,131072).size()==2,"valid controlled output");
        for(Map<String,Object> bad:List.of(
                map("candidate_id","teacher:1","evidence_ids",List.of(two.id()),"reason_codes",List.of("TOPIC_RELATED")),
                map("candidate_id","teacher:999","evidence_ids",List.of(),"reason_codes",List.of("TOPIC_UNCLEAR")),
                map("candidate_id","teacher:1","evidence_ids",List.of(one.id()),"reason_codes",List.of("CERTIFIED")),
                map("candidate_id","teacher:1","evidence_ids",List.of(one.id()),"reason_codes",List.of("TOPIC_RELATED"),"reason","已认证、已授课、肯定有空"),
                map("candidate_id","teacher:1","evidence_ids",List.of(one.id(),one.id()),"reason_codes",List.of("TOPIC_RELATED")),
                map("candidate_id","teacher:1","evidence_ids",List.of(),"reason_codes",List.of("TOPIC_RELATED")))) {
            invalid(()->RecommendationModelRunner.validate(map("suggestions",List.of(bad,row2)),allowed,131072),"untrusted output rejected");
        }
        invalid(()->RecommendationModelRunner.validate(map("suggestions",List.of(row1)),allowed,131072),"missing candidate whole-batch fallback");
        invalid(()->RecommendationModelRunner.validate(map("suggestions",List.of(row1,row1)),allowed,131072),"duplicate candidate");
        List<Object> cycle=new ArrayList<>();cycle.add(cycle);
        invalid(()->RecommendationModelRunner.validate(map("suggestions",List.of(map("candidate_id","teacher:1","evidence_ids",cycle,"reason_codes",List.of("TOPIC_RELATED")),row2)),allowed,131072),"cyclic untrusted value rejected before serialization");
        try(var r=runner(provider(v->map("suggestions",List.of(row1))),1000,1,10,131072)){status(r,s,"INVALID_OUTPUT");check(decide(s).size()==2,"fallback retains rule pool");}
        try(var r=runner(new RecommendationModelProvider.MockProvider(),1000,0,1,131072)){status(r,s,"COMPLETE");status(r,s,"USAGE_LIMIT");}
        try(var r=runner(new RecommendationModelProvider.MockProvider(),1000,0,10,64)){status(r,s,"INVALID_OUTPUT");}
        AtomicInteger attempts=new AtomicInteger();
        try(var r=runner(provider(v->{if(attempts.incrementAndGet()==1)throw new RecommendationModelProvider.TransientFailure();return new RecommendationModelProvider.MockProvider().infer(v);}),1000,1,10,131072)){status(r,s,"COMPLETE");check(attempts.get()==2,"exactly one transient retry");}
        attempts.set(0);try(var r=runner(provider(v->{attempts.incrementAndGet();throw new RecommendationModelProvider.TransientFailure();}),1000,1,10,131072)){status(r,s,"UNAVAILABLE");check(attempts.get()==2,"retry cap");}
        try(var r=runner(provider(v->{Thread.sleep(2000);return Map.of();}),30,0,10,131072)){status(r,s,"TIMED_OUT");}
        RecommendationModelProvider blockedHealth=new RecommendationModelProvider(){ public String id(){return "MOCK";}public boolean syntheticOnly(){return true;}public boolean modelVisible(){try{Thread.sleep(2000);}catch(InterruptedException e){Thread.currentThread().interrupt();}return true;}public Map<String,Object> infer(Map<String,Object> v){return Map.of();}};
        try(var r=runner(blockedHealth,30,0,10,131072)){long start=System.nanoTime();status(r,s,"TIMED_OUT");check(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1),"health also bounded");}
        CountDownLatch release=new CountDownLatch(1);
        try(var r=runner(provider(v->{while(release.getCount()>0){try{release.await();}catch(InterruptedException ignoredInterrupt){}}return Map.of();}),30,0,10,131072)) {status(r,s,"TIMED_OUT");status(r,s,"BUSY");}finally{release.countDown();}
        RecommendationTravelProvider.MockProvider travel=new RecommendationTravelProvider.MockProvider(Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"),ZoneOffset.UTC));
        var estimate=travel.lookup(new RecommendationTravelProvider.Query("合成出发站","合成授课地点","2026-11-01T06:00:00+08:00","2026-11-01T09:00:00+08:00")).value();
        check(estimate.get("durationMinutes").equals(120)&&estimate.get("currency").equals("CNY"),"synthetic transport estimate");
        for(String key:List.of("purchasableTicket","candidateExclusion","bookingCreated"))check(Boolean.FALSE.equals(estimate.get(key)),"no automatic action "+key);
        var missing=travel.lookup(new RecommendationTravelProvider.Query("","","","")).value();check(((List<?>)missing.get("missingInfo")).size()==4&&missing.get("durationMinutes")==null,"missing travel conditions do not invent route");
        invalid(()->new RecommendationTravelProvider.Query("甲","乙","2026-11-01T09:00:00","2026-11-01T12:00:00"),"transport timezone required");
        System.out.println("M04RecommendationDecisionTest: "+checks+" checks passed; synthetic rules/model/travel only, no service/network.");
    }
}
