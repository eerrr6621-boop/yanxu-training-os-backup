package com.training;

import java.util.*;
import static com.training.RecommendationDecision.*;

/** Strict validation of staff-entered structured facts; never interprets source prose. */
final class RecommendationExperienceReview {
    static final String VERSION="experience_review_v1";
    static final int MAX_SOURCES=8, MAX_CLAIMS=4;
    private static final List<String> TOPICS=List.of(
        "亲子沟通","家庭教育","心理健康","情绪管理","压力管理","团队沟通","跨部门沟通",
        "团队建设","团队管理","领导力","战略管理","项目管理","时间管理","目标管理",
        "绩效管理","质量管理","风险管理","安全生产","合规管理","人力资源","人才培养",
        "员工培训","企业文化","组织发展","变革管理","创新思维","设计思维","问题解决",
        "决策分析","数据分析","数字化转型","人工智能","信息安全","网络安全","财务管理",
        "预算管理","成本管理","市场营销","品牌管理","销售技巧","客户服务","商务谈判",
        "商务礼仪","公文写作","演讲表达","职业规划","职业素养","沟通","亲子");
    static final Set<String> ROLE_VALUES=Set.of("TEACHING","LEAD_TEACHING","ASSISTANT","PARTICIPANT","EXPERIENCE");
    static final Set<String> ASSERTION_VALUES=Set.of("AFFIRMED","DENIED","LIMITED","UNKNOWN");
    static final Set<String> AUDIENCE_VALUES=Set.of("NEW_SUPERVISORS","BEGINNERS","EXPERIENCED_PROJECT_MANAGERS","MANAGERS","EMPLOYEES","PARENTS","CHILDREN","UNKNOWN");
    static final Set<String> DEPTH_VALUES=Set.of("INTRODUCTORY","PRACTICAL","ADVANCED","UNKNOWN");
    static final Set<String> LIMIT_VALUES=Set.of("ONLY_PARTICIPATED","ASSISTANT_ONLY","INTRODUCTORY_ONLY","LIMITED_LEAD_EXPERIENCE","LIMITED_TEACHING_EXPERIENCE","ROLE_SCOPE_UNCLEAR","NONE_RECORDED","UNKNOWN");
    static final Set<String> REQUIREMENT_LIMIT_VALUES=Set.of("REQUIRE_LEAD_TEACHING","REQUIRE_TEACHING_EXPERIENCE","REQUIRE_PRACTICAL_EXPERIENCE","EXCLUDE_THEORY_ONLY","INTRODUCTORY_ONLY","ADVANCED_ONLY","ONLY_INCLUDED_TOPICS","NONE_RECORDED","UNKNOWN");
    static List<String> topics(){return TOPICS;}

    /** Normalization validates shape, enum values and contradictions. It does not grant authority. */
    static Map<String,Object> normalize(Map<String,Object> raw) {
        keys(raw,"schema_version","confirmed","reviewed_entire_requirement","reviewed_all_sources","requirement","source_manifest","claims");
        if(!VERSION.equals(raw.get("schema_version")))throw bad("经历核对格式无效");
        for(String field:List.of("confirmed","reviewed_entire_requirement","reviewed_all_sources"))if(!Boolean.TRUE.equals(raw.get(field)))throw bad("请明确完成本次需求及所有来源的人工核对");
        Map<String,Object> requirement=object(raw.get("requirement"));keys(requirement,"topic","audience","depth","limits");
        String topic=topic(requirement.get("topic")),audience=code(requirement.get("audience"),AUDIENCE_VALUES),depth=code(requirement.get("depth"),DEPTH_VALUES);
        List<String> requirementLimits=limits(requirement.get("limits"),REQUIREMENT_LIMIT_VALUES);
        if(requirementLimits.contains("INTRODUCTORY_ONLY")&&(depth.equals("ADVANCED")||requirementLimits.contains("ADVANCED_ONLY"))||requirementLimits.contains("ADVANCED_ONLY")&&depth.equals("INTRODUCTORY"))throw bad("需求深度与限制相互矛盾");
        Map<String,Object> normalizedRequirement=frozen(map("topic",topic,"audience",audience,"depth",depth,"limits",requirementLimits));
        List<?> manifest=list(raw.get("source_manifest"),MAX_SOURCES);Map<String,String> versions=new TreeMap<>();
        for(Object entry:manifest){Map<String,Object> source=object(entry);keys(source,"evidence_id","source_version");String id=evidenceId(source.get("evidence_id")),version=version(source.get("source_version"));if(versions.putIfAbsent(id,version)!=null)throw bad("来源清单含重复证据");}
        List<Map<String,Object>> normalizedManifest=new ArrayList<>();versions.forEach((id,version)->normalizedManifest.add(frozen(map("evidence_id",id,"source_version",version))));
        List<Map<String,Object>> claims=new ArrayList<>();Set<String> duplicates=new HashSet<>();
        for(Object entry:list(raw.get("claims"),MAX_CLAIMS)){
            Map<String,Object> claim=object(entry);keys(claim,"evidence_id","source_version","topic","role","assertion","audience","depth","limits","confirmed");
            String id=evidenceId(claim.get("evidence_id")),version=version(claim.get("source_version"));
            if(!version.equals(versions.get(id))||!Boolean.TRUE.equals(claim.get("confirmed"))||!topic.equals(topic(claim.get("topic"))))throw bad("经历声明须对应当前来源、已核课程及明确确认");
            String role=code(claim.get("role"),ROLE_VALUES),assertion=code(claim.get("assertion"),ASSERTION_VALUES),claimAudience=code(claim.get("audience"),AUDIENCE_VALUES),claimDepth=code(claim.get("depth"),DEPTH_VALUES);
            List<String> claimLimits=limits(claim.get("limits"),LIMIT_VALUES);
            if(claimLimits.contains("INTRODUCTORY_ONLY")&&claimDepth.equals("ADVANCED"))throw bad("经历深度与限制相互矛盾");
            if(claimLimits.contains("ONLY_PARTICIPATED")&&claimLimits.contains("ASSISTANT_ONLY"))throw bad("仅参与与仅任助教不能同时成立");
            if(assertion.equals("DENIED")&&(role.equals("LEAD_TEACHING")&&claimLimits.contains("LIMITED_LEAD_EXPERIENCE")||role.equals("TEACHING")&&(claimLimits.contains("LIMITED_TEACHING_EXPERIENCE")||claimLimits.contains("LIMITED_LEAD_EXPERIENCE"))||role.equals("EXPERIENCE")&&(claimLimits.contains("LIMITED_TEACHING_EXPERIENCE")||claimLimits.contains("LIMITED_LEAD_EXPERIENCE"))))throw bad("同一经历不能既明确无记录又记录为有限或不足");
            if(assertion.equals("AFFIRMED")&&(claimLimits.contains("ONLY_PARTICIPATED")&&!role.equals("PARTICIPANT")||claimLimits.contains("ASSISTANT_ONLY")&&!role.equals("ASSISTANT")))throw bad("经历角色与仅限角色相互矛盾");
            Map<String,Object> normalized=frozen(map("evidence_id",id,"source_version",version,"topic",topic,"role",role,"assertion",assertion,"audience",claimAudience,"depth",claimDepth,"limits",claimLimits,"confirmed",true));
            if(!duplicates.add(Json.write(normalized)))throw bad("经历声明重复");claims.add(normalized);
        }
        for(int i=0;i<claims.size();i++)for(int j=i+1;j<claims.size();j++)if(conflicts(claims.get(i),claims.get(j)))throw bad("本课程核对事实存在范围重叠的肯定与否定，请先人工澄清");
        claims.sort(Comparator.comparing(Json::write));
        return frozen(map("schema_version",VERSION,"confirmed",true,"reviewed_entire_requirement",true,"reviewed_all_sources",true,"requirement",normalizedRequirement,"source_manifest",List.copyOf(normalizedManifest),"claims",List.copyOf(claims)));
    }

    /** Full-source equality prevents stale, omitted or cross-candidate evidence from being exported. */
    static void validateSources(Map<String,Object> review,String candidateId,List<Evidence> sources) {
        Map<String,Object> checked=normalize(review);
        if(candidateId==null||!candidateId.matches("teacher:[1-9][0-9]*")||sources==null||sources.size()>MAX_SOURCES)throw bad("经历核对来源数量或候选归属无效");
        Map<String,String> expected=new TreeMap<>();
        for(Evidence source:sources){if(source==null||!candidateId.equals(source.candidateId())||expected.putIfAbsent(source.id(),source.sourceVersion())!=null)throw bad("经历来源归属或唯一性无效");}
        Map<String,String> actual=new TreeMap<>();for(Object raw:(List<?>)checked.get("source_manifest")){Map<String,Object> source=object(raw);actual.put((String)source.get("evidence_id"),(String)source.get("source_version"));}
        if(!expected.equals(actual))throw bad("完整来源清单或版本已变化，请重新人工核对");
    }

    private static boolean conflicts(Map<String,Object> a,Map<String,Object> b){
        String left=(String)a.get("assertion"),right=(String)b.get("assertion");
        if(!(left.equals("AFFIRMED")&&right.equals("DENIED")||left.equals("DENIED")&&right.equals("AFFIRMED")))return false;
        Map<String,Object> denied=left.equals("DENIED")?a:b,affirmed=left.equals("AFFIRMED")?a:b;
        String no=(String)denied.get("role"),yes=(String)affirmed.get("role");
        // These audience/depth labels do not prove disjoint scope (e.g. practical and advanced).
        // Without an explicit reviewed event scope, never use differing labels to erase a conflict.
        return no.equals(yes)||no.equals("EXPERIENCE")||no.equals("TEACHING")&&yes.equals("LEAD_TEACHING");
    }
    private static List<String> limits(Object value,Set<String> allowed){List<?> raw=list(value,allowed.size());if(raw.isEmpty())throw bad("限制须明确选择未知、未记录限制或具体枚举");SortedSet<String> result=new TreeSet<>();for(Object item:raw)if(!result.add(code(item,allowed)))throw bad("限制枚举重复");if(result.size()>1&&(result.contains("UNKNOWN")||result.contains("NONE_RECORDED")))throw bad("未知或未记录限制不能与其他限制并列");return List.copyOf(result);}
    private static String topic(Object value){return code(value,new HashSet<>(TOPICS));}
    private static String code(Object value,Set<String> allowed){if(!(value instanceof String text)||!allowed.contains(text))throw bad("经历核对含未知枚举");return text;}
    private static String evidenceId(Object value){if(!(value instanceof String text)||!text.matches("ev:[a-f0-9]{64}"))throw bad("经历证据编号无效");return text;}
    private static String version(Object value){if(!(value instanceof String text)||text.isBlank()||text.length()>128||text.chars().anyMatch(Character::isISOControl))throw bad("来源版本无效");return text;}
    private static List<?> list(Object value,int maximum){if(!(value instanceof List<?> result)||result.size()>maximum)throw bad("经历核对列表无效或超过上限");return result;}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object value){if(!(value instanceof Map<?,?> raw)||raw.keySet().stream().anyMatch(k->!(k instanceof String)))throw bad("经历核对对象无效");return (Map<String,Object>)raw;}
    private static void keys(Map<String,Object> value,String... keys){if(value==null||!value.keySet().equals(Set.of(keys)))throw bad("经历核对字段缺失或包含未知字段");}
    private static Map<String,Object> frozen(Map<String,Object> value){return Collections.unmodifiableMap(value);}
    private static RecommendationCpaPayload.PrivacyException bad(String message){return new RecommendationCpaPayload.PrivacyException(message);}
    private RecommendationExperienceReview(){}
}
