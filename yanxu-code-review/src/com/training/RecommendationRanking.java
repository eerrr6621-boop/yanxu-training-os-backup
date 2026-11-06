package com.training;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.training.RecommendationDecision.*;

/** Pure local preferences after authoritative eligibility checks. No network, model, booking or names as keys. */
final class RecommendationRanking {
    static final String RULE_VERSION = "recommendation_ranking_v2_20261029_completeness";
    enum IdentityStatus { CONFIRMED, UNKNOWN, INVALID }
    enum TransportMode { LOCAL, RAIL, AIR, OTHER, UNKNOWN }
    enum WindowBasis { CONFIRMED_TRAVEL_BUFFERS, ONLINE_NO_TRAVEL, UNKNOWN }
    enum Tier { LOCAL_OR_ONLINE, RAIL_WITHIN_180, AIR_WITHIN_150, OTHER_FEASIBLE, UNCONFIRMED }

    record Window(Instant start, Instant end) {
        Window {
            Objects.requireNonNull(start); Objects.requireNonNull(end);
            if (!start.isBefore(end)) throw invalid("完整时段无效");
        }
        boolean overlaps(Window other) { return start.isBefore(other.end) && other.start.isBefore(end); }
    }
    record Context(String requestId, boolean online, String teachingPlace, Window teachingWindow) {
        Context { requestId=bounded(requestId,128,false); teachingPlace=bounded(teachingPlace,200,true); }
    }
    /** Coverage must be the same before two cost facts are comparable. Unknown is null, never zero. */
    record Cost(BigDecimal amount, String currency, String coverage) {
        Cost {
            Objects.requireNonNull(amount);
            if (amount.signum()<0 || amount.compareTo(new BigDecimal("1000000000"))>0 || amount.scale()>6 ||
                    currency==null || !currency.matches("[A-Z]{3}") ||
                    coverage==null || !Set.of("TRAVEL_ONLY","FEE_ONLY","TRAVEL_AND_FEE").contains(coverage)) throw invalid("费用事实无效");
        }
    }
    /** Source/time stay in the host audit. These are explicit facts for this teacher and this request only. */
    record TravelFacts(long teacherId, String requestId, Confirmation feasibility, TransportMode mode,
                       String departurePlace, String teachingPlace, Integer legMinutes, Integer doorToDoorMinutes,
                       Integer beforeBufferMinutes, Integer afterBufferMinutes, Long distanceMeters, Cost cost) {
        TravelFacts {
            teacherKey(teacherId); requestId=bounded(requestId,128,false);
            Objects.requireNonNull(feasibility); Objects.requireNonNull(mode);
            departurePlace=bounded(departurePlace,200,true); teachingPlace=bounded(teachingPlace,200,true);
            minutes(legMinutes); minutes(doorToDoorMinutes); minutes(beforeBufferMinutes); minutes(afterBufferMinutes);
            if (legMinutes!=null && doorToDoorMinutes!=null && doorToDoorMinutes<legMinutes) throw invalid("门到门时长不能小于所含车程或航程");
            if (distanceMeters!=null && (distanceMeters<0 || distanceMeters>1000000000L)) throw invalid("距离事实无效");
        }
    }
    record Input(Decision decision, IdentityStatus identity, TravelFacts travel) {
        Input { Objects.requireNonNull(decision); Objects.requireNonNull(decision.candidate()); Objects.requireNonNull(decision.state()); Objects.requireNonNull(identity); }
    }
    record Ranked(long teacherId, String candidateId, String requestId, State state, Tier tier, Window occupiedWindow, WindowBasis windowBasis,
                  List<String> reasonCodes, List<String> missingInfo) {
        Ranked {
            teacherKey(teacherId);
            if (!Objects.equals(candidateId,"teacher:"+teacherId)) throw invalid("候选身份标识不一致");
            requestId=bounded(requestId,128,false); Objects.requireNonNull(state); Objects.requireNonNull(tier); Objects.requireNonNull(windowBasis);
            if (state==State.PASS && (occupiedWindow==null || tier==Tier.UNCONFIRMED || windowBasis==WindowBasis.UNKNOWN)) throw invalid("正常候选缺少完整占用窗口或交通梯度");
            reasonCodes=List.copyOf(reasonCodes); missingInfo=List.copyOf(missingInfo);
        }
    }
    record Result(String ruleVersion, List<Ranked> normal, List<Ranked> pending, List<Ranked> excluded,
                  List<Ranked> topThree, List<Long> explanationShortlist, int scanned, int shortage) {
        Result {
            normal=List.copyOf(normal); pending=List.copyOf(pending); excluded=List.copyOf(excluded);
            topThree=List.copyOf(topThree); explanationShortlist=List.copyOf(explanationShortlist);
        }
    }
    private record Entry(Ranked ranked, TravelFacts travel) {}
    private static final Set<String> NEGATIVE_QUALIFICATION=Set.of("NOT_CERTIFIED","CERTIFICATION_REVOKED","NOT_YET_VALID","EXPIRED","LEVEL_NOT_ALLOWED","CITY_NOT_ALLOWED","TEACHER_NOT_ACTIVE","COURSE_INACTIVE");

    static Result rank(Context context, List<Input> inputs) {
        Objects.requireNonNull(context); inputs=List.copyOf(inputs);
        if (inputs.size()>500) throw invalid("候选超过本轮500位上限");
        Set<Long> ids=new HashSet<>(); List<Entry> normal=new ArrayList<>(); List<Ranked> pending=new ArrayList<>(),excluded=new ArrayList<>();
        for (Input input:inputs) {
            Objects.requireNonNull(input); long id=input.decision.candidate().teacherId();
            if (!ids.add(id)) throw invalid("同一需求中候选身份重复");
            Ranked ranked=evaluate(context,input);
            if (ranked.state==State.PASS) normal.add(new Entry(ranked,input.travel));
            else (ranked.state==State.PENDING?pending:excluded).add(ranked);
        }
        List<Ranked> ordered=new ArrayList<>();
        for (Tier tier:Tier.values()) for (int count=2;count>=0;count--) {
            final int known=count;
            ordered.addAll(orderComparable(normal.stream().filter(e->e.ranked.tier==tier && knownMetrics(e)==known).toList()));
        }
        Comparator<Ranked> identityOrder=Comparator.comparingLong(Ranked::teacherId); pending.sort(identityOrder); excluded.sort(identityOrder);
        List<Long> shortlist=new ArrayList<>();
        for (Ranked ranked:ordered) if(shortlist.size()<6) shortlist.add(ranked.teacherId);
        for (Ranked ranked:pending) if(shortlist.size()<6) shortlist.add(ranked.teacherId);
        return new Result(RULE_VERSION,ordered,pending,excluded,ordered.subList(0,Math.min(3,ordered.size())),shortlist,inputs.size(),Math.max(0,3-ordered.size()));
    }

    private static Ranked evaluate(Context context, Input input) {
        Decision decision=input.decision; Candidate c=decision.candidate(); TravelFacts travel=input.travel;
        if (travel!=null && (travel.teacherId!=c.teacherId() || !travel.requestId.equals(context.requestId))) throw invalid("出行事实不属于本次候选或需求");
        if (travel!=null && !travel.teachingPlace.isBlank() && !context.teachingPlace.isBlank() && !travel.teachingPlace.equals(context.teachingPlace)) throw invalid("出行目的地与本次需求不一致");
        LinkedHashSet<String> reasons=new LinkedHashSet<>(),missing=new LinkedHashSet<>(); State state=decision.state();
        if (state==State.EXCLUDED) reasons.add("KNOWN_RULE_EXCLUSION");
        if (state==State.PENDING) missing.add("BASE_RULE_PENDING");
        for (Check check:decision.checks()) {
            Objects.requireNonNull(check); Objects.requireNonNull(check.state());
            state=combine(state,check.state());
            if (check.state()==State.EXCLUDED) reasons.add("KNOWN_RULE_EXCLUSION");
            if (check.state()==State.PENDING) missing.add("BASE_RULE_PENDING");
        }
        if (!"在库".equals(c.status())) { state=combine(state,"出库".equals(c.status())?State.EXCLUDED:State.PENDING); reasons.add("TEACHER_STATUS_CHECK"); }
        if (c.qualificationCodes().stream().anyMatch(NEGATIVE_QUALIFICATION::contains)) { state=State.EXCLUDED; reasons.add("EXPLICIT_QUALIFICATION_FAILURE"); }
        else if (!c.qualificationCodes().isEmpty()) { state=combine(state,State.PENDING); missing.add("QUALIFICATION_UNKNOWN"); }
        if (c.service()!=Confirmation.CONFIRMED) { state=combine(state,c.service()==Confirmation.DECLINED?State.EXCLUDED:State.PENDING); reasons.add("SERVICE_NOT_CONFIRMED"); }
        if (c.availability()!=Confirmation.CONFIRMED) { state=combine(state,c.availability()==Confirmation.DECLINED?State.EXCLUDED:State.PENDING); reasons.add("AVAILABILITY_NOT_CONFIRMED"); }
        if (input.identity==IdentityStatus.INVALID) { state=State.EXCLUDED; reasons.add("IDENTITY_INVALID"); }
        if (input.identity==IdentityStatus.UNKNOWN) { state=combine(state,State.PENDING); missing.add("IDENTITY_UNCONFIRMED"); }
        if (context.teachingWindow==null) { state=combine(state,State.PENDING); missing.add("TEACHING_WINDOW_UNKNOWN"); }
        Tier tier=Tier.UNCONFIRMED; Window occupied=null; WindowBasis basis=WindowBasis.UNKNOWN;
        if (context.online) {
            tier=Tier.LOCAL_OR_ONLINE; occupied=context.teachingWindow; basis=WindowBasis.ONLINE_NO_TRAVEL; reasons.add("ONLINE_NO_TRAVEL_REQUIRED");
        } else if (travel==null) {
            state=combine(state,State.PENDING); missing.add("TRAVEL_FACTS_UNKNOWN");
        } else {
            if (travel.feasibility==Confirmation.DECLINED) { state=State.EXCLUDED; reasons.add("TRAVEL_DECLINED"); }
            if (travel.feasibility==Confirmation.UNKNOWN) { state=combine(state,State.PENDING); missing.add("TRAVEL_FEASIBILITY_UNKNOWN"); }
            if (travel.departurePlace.isBlank()) missing.add("ACTUAL_DEPARTURE_UNKNOWN");
            if (context.teachingPlace.isBlank() || travel.teachingPlace.isBlank()) missing.add("TEACHING_PLACE_UNKNOWN");
            if (travel.mode==TransportMode.UNKNOWN) missing.add("TRAVEL_MODE_UNKNOWN");
            if (travel.doorToDoorMinutes==null) missing.add("DOOR_TO_DOOR_UNKNOWN");
            if (travel.beforeBufferMinutes==null) missing.add("BEFORE_BUFFER_UNKNOWN");
            if (travel.afterBufferMinutes==null) missing.add("AFTER_BUFFER_UNKNOWN");
            if (missing.stream().anyMatch(v->Set.of("ACTUAL_DEPARTURE_UNKNOWN","TEACHING_PLACE_UNKNOWN","TRAVEL_MODE_UNKNOWN","DOOR_TO_DOOR_UNKNOWN","BEFORE_BUFFER_UNKNOWN","AFTER_BUFFER_UNKNOWN").contains(v))) state=combine(state,State.PENDING);
            if (context.teachingWindow!=null && travel.beforeBufferMinutes!=null && travel.afterBufferMinutes!=null) {
                try { occupied=new Window(context.teachingWindow.start.minusSeconds(travel.beforeBufferMinutes*60L),context.teachingWindow.end.plusSeconds(travel.afterBufferMinutes*60L)); }
                catch (DateTimeException overflow) { throw invalid("出行缓冲窗口超出可表示范围"); }
            }
            if (travel.feasibility==Confirmation.CONFIRMED && state!=State.PENDING && state!=State.EXCLUDED) {
                basis=WindowBasis.CONFIRMED_TRAVEL_BUFFERS;
                tier=travel.mode==TransportMode.LOCAL?Tier.LOCAL_OR_ONLINE:
                        travel.mode==TransportMode.RAIL && travel.legMinutes!=null && travel.legMinutes<=180?Tier.RAIL_WITHIN_180:
                        travel.mode==TransportMode.AIR && travel.legMinutes!=null && travel.legMinutes<=150?Tier.AIR_WITHIN_150:Tier.OTHER_FEASIBLE;
            }
            reasons.add("LEG_AND_DOOR_TO_DOOR_SEPARATE");
            if (travel.legMinutes==null && (travel.mode==TransportMode.RAIL || travel.mode==TransportMode.AIR)) missing.add("PREFERENCE_LEG_DURATION_UNKNOWN");
            if (travel.distanceMeters==null) missing.add("DISTANCE_UNKNOWN");
            if (travel.cost==null) missing.add("COST_UNKNOWN");
        }
        if (state==State.PASS) reasons.add("PREFERENCE_"+tier.name());
        return new Ranked(c.teacherId(),c.id(),context.requestId,state,tier,occupied,basis,List.copyOf(reasons),List.copyOf(missing));
    }

    private static int knownMetrics(Entry entry) {
        return entry.travel==null?0:(entry.travel.distanceMeters==null?0:1)+(entry.travel.cost==null?0:1);
    }
    /** Completeness is handled before Pareto ordering; unknown values remain unknown.
     * Pareto order only within identical known-metric coverage. Incomparable/missing facts use stable ID, not zero. */
    private static List<Ranked> orderComparable(List<Entry> entries) {
        int n=entries.size(); List<List<Integer>> edges=new ArrayList<>(); int[] indegree=new int[n];
        for(int i=0;i<n;i++) edges.add(new ArrayList<>());
        for(int i=0;i<n;i++) for(int j=0;j<n;j++) if(i!=j && dominates(entries.get(i),entries.get(j))) { edges.get(i).add(j); indegree[j]++; }
        PriorityQueue<Integer> ready=new PriorityQueue<>(Comparator.comparingLong(i->entries.get(i).ranked.teacherId));
        for(int i=0;i<n;i++) if(indegree[i]==0) ready.add(i);
        List<Ranked> result=new ArrayList<>();
        while(!ready.isEmpty()) { int i=ready.remove(); result.add(entries.get(i).ranked); for(int j:edges.get(i)) if(--indegree[j]==0) ready.add(j); }
        if(result.size()!=n) throw new IllegalStateException("Comparable preference cycle");
        return result;
    }
    private static boolean dominates(Entry a,Entry b) {
        TravelFacts x=a.travel,y=b.travel; if(x==null || y==null) return false;
        if((x.distanceMeters==null)!=(y.distanceMeters==null) || (x.cost==null)!=(y.cost==null)) return false;
        if(x.cost!=null && (!x.cost.currency.equals(y.cost.currency) || !x.cost.coverage.equals(y.cost.coverage))) return false;
        boolean strict=false;
        if(x.distanceMeters!=null) { int c=Long.compare(x.distanceMeters,y.distanceMeters); if(c>0)return false; strict|=c<0; }
        if(x.cost!=null) { int c=x.cost.amount.compareTo(y.cost.amount); if(c>0)return false; strict|=c<0; }
        return strict;
    }
    private static State combine(State a,State b) { return a==State.EXCLUDED || b==State.EXCLUDED?State.EXCLUDED:a==State.PENDING || b==State.PENDING?State.PENDING:State.PASS; }
    private static void minutes(Integer value) { if(value!=null && (value<0 || value>20160)) throw invalid("交通时长或缓冲超出技术上限"); }
    private static void teacherKey(long value) { if(value<1 || value>9007199254740991L) throw invalid("稳定教师编号无效"); }
}
