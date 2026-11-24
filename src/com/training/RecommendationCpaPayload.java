package com.training;

import java.util.*;
import static com.training.RecommendationDecision.*;

/** Export only explicitly recorded structured facts. Raw source text is never interpreted or copied. */
final class RecommendationCpaPayload {
    static final String VERSION="cpa-staff-confirmed-projection-v3";
    static final int MAX_CANDIDATES=6, MAX_EVIDENCE=8, MAX_TAGS=1;
    static final class PrivacyException extends IllegalArgumentException {
        private static final long serialVersionUID=1L;
        private final String status;
        PrivacyException(String reason){this("PRIVACY_REJECTED",reason);}
        PrivacyException(String status,String reason){super(reason);this.status=status;}
        String status(){return status;}
    }
    /** Host constructs this from current authorized saved review events, never from the advice body. */
    record ReviewedProjection(Map<String,Map<String,Object>> reviews) {
        ReviewedProjection {if(reviews==null)throw refused("经历核对投影缺失");Map<String,Map<String,Object>> copy=new LinkedHashMap<>();for(var entry:reviews.entrySet()){if(entry.getKey()==null||!entry.getKey().matches("teacher:[1-9][0-9]*"))throw refused("经历核对候选标识无效");copy.put(entry.getKey(),RecommendationExperienceReview.normalize(entry.getValue()));}reviews=Collections.unmodifiableMap(copy);}
    }
    /** Only request() may cross the transport boundary. Original evidence and IDs remain local. */
    record Prepared(Map<String,Object> request,Map<String,Map<String,Evidence>> allowed,
                    Map<String,String> candidateAliases,Map<String,String> evidenceAliases,Map<String,Object> filter) {}

    /** Retained source compatibility does not authorize any free-text inference. */
    static Prepared prepare(Snapshot snapshot,List<Decision> selected){throw noConfirmed();}
    static List<String> localRequiredTopics(String ignored){return List.of();}
    static List<String> recallTerms(String ignored){return List.of();}
    static boolean localTopicMatches(String ignored,String ignoredTopic){return false;}
    static void inspectRecallSource(String ignored){/* No source parsing: only a complete, locally reviewed manifest may be used. */}

    static Prepared prepare(Snapshot snapshot,List<Decision> selected,ReviewedProjection projection){
        if(snapshot==null||selected==null||selected.isEmpty()||selected.size()>MAX_CANDIDATES||projection==null)throw refused("短名单须为一至六位当前候选，并提供服务端经历核对记录");
        Map<String,Candidate> captured=new HashMap<>();for(Candidate c:snapshot.candidates())captured.put(c.id(),c);
        Set<String> selectedIds=new LinkedHashSet<>();
        for(Decision d:selected)if(d==null||d.candidate()==null||d.state()==State.EXCLUDED||!d.candidate().equals(captured.get(d.candidate().id()))||!selectedIds.add(d.candidate().id()))throw refused("短名单归属、状态或当前资料版本无效");
        if(!selectedIds.containsAll(projection.reviews().keySet()))throw refused("经历核对含短名单以外候选");
        Map<String,Object> requirement=null;String requirementSignature=null;int usable=0;
        // Validate every saved review first. Never silently remove a stale or contradictory candidate.
        for(Decision d:selected){Map<String,Object> review=projection.reviews().get(d.candidate().id());if(review==null)continue;
            RecommendationExperienceReview.validateSources(review,d.candidate().id(),d.candidate().evidence());
            Map<String,Object> current=RecommendationExperienceReview.object(review.get("requirement"));String signature=Json.write(current);
            if(requirementSignature!=null&&!requirementSignature.equals(signature))throw refused("候选的已核课程需求不同，请统一核对后比较");
            requirement=current;requirementSignature=signature;
            for(Object raw:(List<?>)review.get("claims"))if(!"UNKNOWN".equals(RecommendationExperienceReview.object(raw).get("assertion")))usable++;
        }
        if(usable==0||requirement==null)throw noConfirmed();
        Map<String,Map<String,Evidence>> allowed=new LinkedHashMap<>();Map<String,String> candidateAliases=new LinkedHashMap<>(),evidenceAliases=new LinkedHashMap<>();
        List<Map<String,Object>> candidates=new ArrayList<>();int evidenceSequence=0,claimCount=0,unreviewedCandidates=0;
        for(Decision d:selected){Candidate c=d.candidate();String candidateAlias="C"+(candidates.size()+1);candidateAliases.put(candidateAlias,c.id());
            Map<String,Evidence> owned=new LinkedHashMap<>();List<Map<String,Object>> evidenceRows=new ArrayList<>();Map<String,Object> review=projection.reviews().get(c.id());
            if(review==null)unreviewedCandidates++;
            else for(Evidence e:c.evidence()){
                List<Map<String,Object>> claims=new ArrayList<>();
                for(Object raw:(List<?>)review.get("claims")){Map<String,Object> recorded=RecommendationExperienceReview.object(raw);if(!e.id().equals(recorded.get("evidence_id")))continue;
                    claims.add(frozen(map("topic",recorded.get("topic"),"role",recorded.get("role"),"assertion",recorded.get("assertion"),"audience",recorded.get("audience"),"depth",recorded.get("depth"),"limits",recorded.get("limits"),"verification","STAFF_CONFIRMED")));
                }
                String summary=summary(claims);if(summary.length()>1200)throw refused("已核经历超过摘要上限，不能截断事实");
                String alias="E"+(++evidenceSequence);claimCount+=claims.size();evidenceRows.add(frozen(map("evidence_id",alias,"kind",e.kind(),"summary",summary,"claims",List.copyOf(claims))));owned.put(alias,e);evidenceAliases.put(alias,e.id());
            }
            allowed.put(candidateAlias,Collections.unmodifiableMap(owned));
            candidates.add(frozen(map("candidate_id",candidateAlias,"course_tags",review==null?List.of():List.of(requirement.get("topic")),"evidence",List.copyOf(evidenceRows))));
        }
        Map<String,Object> request=frozen(map("schema_version","topic_advisory_staff_confirmed_v3","course_requirement",requirement,"candidates",List.copyOf(candidates)));
        Map<String,Object> filter=frozen(map("projectionVersion",VERSION,"sourceTextCopied",false,"candidates",candidates.size(),"sourceFieldsReviewed",0,"evidenceCount",evidenceSequence,"claimCount",claimCount,"droppedEvidenceCount",0,"unreviewedCandidates",unreviewedCandidates,"projectionLoss",true));
        return new Prepared(request,Collections.unmodifiableMap(allowed),Collections.unmodifiableMap(candidateAliases),Collections.unmodifiableMap(evidenceAliases),filter);
    }
    private static String summary(List<Map<String,Object>> claims){
        if(claims.isEmpty())return "本来源已由操作人核对，未记录本课程结构化经历声明；不据此判断能力或经历。";
        List<String> lines=new ArrayList<>();for(Map<String,Object> c:claims){List<String> limits=new ArrayList<>();for(Object limit:(List<?>)c.get("limits"))limits.add(label((String)limit));lines.add(c.get("topic")+"：操作人记录角色["+label((String)c.get("role"))+"]；声明["+label((String)c.get("assertion"))+"]；对象["+label((String)c.get("audience"))+"]；深度["+label((String)c.get("depth"))+"]；限制["+String.join("、",limits)+"]。本次人工记录不等于教师签认、课程资格或派课。");}return String.join("",lines);
    }
    private static String label(String value){return switch(value){
        case "TEACHING"->"讲授";case "LEAD_TEACHING"->"主讲";case "ASSISTANT"->"助教";case "PARTICIPANT"->"参与或学习";case "EXPERIENCE"->"相关经历";
        case "AFFIRMED"->"明确有此经历";case "DENIED"->"明确无此经历";case "LIMITED"->"此经历有限或不足";case "UNKNOWN"->"未知";
        case "NEW_SUPERVISORS"->"新任主管";case "BEGINNERS"->"初学者";case "EXPERIENCED_PROJECT_MANAGERS"->"资深项目经理";case "MANAGERS"->"管理人员";case "EMPLOYEES"->"员工";case "PARENTS"->"家长";case "CHILDREN"->"儿童";
        case "INTRODUCTORY"->"入门";case "PRACTICAL"->"实践";case "ADVANCED"->"进阶";case "ONLY_PARTICIPATED"->"仅参与或学习";case "ASSISTANT_ONLY"->"仅任助教";case "INTRODUCTORY_ONLY"->"仅限入门";case "LIMITED_LEAD_EXPERIENCE"->"主讲经历不足";case "LIMITED_TEACHING_EXPERIENCE"->"讲授经历不足";case "ROLE_SCOPE_UNCLEAR"->"角色范围不明确";case "NONE_RECORDED"->"未记录限制";default->throw refused("已核经历含未允许枚举");};}
    private static PrivacyException noConfirmed(){return new PrivacyException("NO_CONFIRMED_EXPERIENCE","没有当前有效且明确的已核结构化经历；原文不自动生成经历事实，本次不调用AI");}
    private static PrivacyException refused(String message){return new PrivacyException(message);}
    private static Map<String,Object> frozen(Map<String,Object> value){return Collections.unmodifiableMap(value);}
    private RecommendationCpaPayload(){}
}
