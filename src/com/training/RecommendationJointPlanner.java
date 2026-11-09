package com.training;

import java.util.*;
import static com.training.RecommendationDecision.*;
import static com.training.RecommendationRanking.*;

/** Read-only bounded suggestions. The only objective is coverage, not global cost or institution priority. */
final class RecommendationJointPlanner {
    static final String RULE_VERSION="recommendation_joint_v2_20261027";
    enum CoverageProof { UPPER_BOUND_REACHED, EXHAUSTED, BUDGET_LIMITED }
    record Request(String requestId,List<Ranked> candidates) {
        Request {
            requestId=bounded(requestId,128,false); candidates=List.copyOf(candidates);
            if(candidates.size()>500)throw invalid("联合候选超过500位上限");
            Set<Long> ids=new HashSet<>();
            for(Ranked candidate:candidates) if(!candidate.requestId().equals(requestId) || candidate.state()!=State.PASS || candidate.occupiedWindow()==null || candidate.windowBasis()==WindowBasis.UNKNOWN || !ids.add(candidate.teacherId())) throw invalid("联合建议只能接收本需求完整且不重复的正常候选");
        }
    }
    record Choice(String requestId,Long teacherId,String reasonCode) {}
    record Plan(String ruleVersion,List<Choice> choices,int assignedCount,long visitedNodes,boolean limited,CoverageProof coverageProof) {
        Plan { choices=List.copyOf(choices); }
    }
    static Plan solve(List<Request> requests,long maxVisitedNodes) {
        requests=List.copyOf(requests);
        if(requests.isEmpty() || requests.size()>3 || maxVisitedNodes<1 || maxVisitedNodes>1000000)throw invalid("联合需求数量或搜索预算无效");
        Set<String> ids=new HashSet<>(); for(Request request:requests) if(!ids.add(request.requestId))throw invalid("联合需求标识重复");
        Search search=new Search(requests,maxVisitedNodes); search.walk(0,0);
        List<Choice> choices=new ArrayList<>();
        for(int i=0;i<requests.size();i++) {
            int index=search.best[i]; Request request=requests.get(i);
            String reason=index<0?(request.candidates.isEmpty()?"NO_NORMAL_CANDIDATE":search.limited?"UNASSIGNED_SEARCH_LIMIT":"NO_COMPATIBLE_CANDIDATE"):index==0?"FIRST_COMPATIBLE_CHOICE":"JOINT_CONFLICT_ALTERNATIVE";
            choices.add(new Choice(request.requestId,index<0?null:request.candidates.get(index).teacherId(),reason));
        }
        return new Plan(RULE_VERSION,choices,search.bestCount,search.visited,search.limited,
                search.limited?CoverageProof.BUDGET_LIMITED:search.bestCount==requests.size()?CoverageProof.UPPER_BOUND_REACHED:CoverageProof.EXHAUSTED);
    }
    private static final class Search {
        final List<Request> requests; final long budget; final int[] current; int[] best;
        int bestCount; long visited; boolean limited,complete;
        Search(List<Request> requests,long budget) { this.requests=requests;this.budget=budget;current=new int[requests.size()];Arrays.fill(current,-1);best=current.clone(); }
        boolean tick() { if(visited>=budget){limited=true;return false;}visited++;return true; }
        void walk(int index,int count) {
            if(complete || limited)return;
            if(count>bestCount) { bestCount=count;best=current.clone();if(count==requests.size()){complete=true;return;} }
            if(index==requests.size() || count+requests.size()-index<=bestCount)return;
            List<Ranked> options=requests.get(index).candidates;
            for(int i=0;i<options.size();i++) {
                if(!tick())return; Ranked next=options.get(i);boolean compatible=true;
                for(int j=0;j<index;j++) if(current[j]>=0) {
                    Ranked prior=requests.get(j).candidates.get(current[j]);
                    if(next.teacherId()==prior.teacherId() && next.occupiedWindow().overlaps(prior.occupiedWindow())){compatible=false;break;}
                }
                if(compatible) { current[index]=i;walk(index+1,count+1);current[index]=-1;if(complete || limited)return; }
            }
            if(tick()) { current[index]=-1;walk(index+1,count); }
        }
    }
}
