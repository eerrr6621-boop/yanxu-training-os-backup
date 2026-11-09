package com.training;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;
import static com.training.RecommendationDecision.*;

/** Optional bounded local CPA assistance. No rule writes and no source prompt logging. */
final class RecommendationCpaAdvisor {
    static final String VERSION="cpa-advisory-v4-staff-confirmed-facts-20261027";
    interface Client { RecommendationCpaTransport.Result complete(Map<String,Object> payload)throws Exception; }
    interface Revalidate { void check()throws Exception; }
    private final boolean enabled;private final Client client;private final LongSupplier clock;private final int timeoutMillis;private final long cycleStartedAt;
    private final Semaphore slot=new Semaphore(1);private long window;private int minuteCalls,totalCalls,minuteBytes;private String lastLimit="NONE";
    RecommendationCpaAdvisor(boolean enabled,Client client,LongSupplier clock){this(enabled,client,clock,45000);}
    RecommendationCpaAdvisor(boolean enabled,Client client,LongSupplier clock,int timeoutMillis){this.enabled=enabled;this.client=Objects.requireNonNull(client);this.clock=clock;this.timeoutMillis=timeoutMillis;this.cycleStartedAt=clock.getAsLong();}
    static RecommendationCpaAdvisor configured(){if(!Boolean.getBoolean("recommendation.cpa.enabled"))return new RecommendationCpaAdvisor(false,payload->{throw new IllegalStateException("CPA disabled");},System::currentTimeMillis);var transport=new RecommendationCpaTransport();return new RecommendationCpaAdvisor(true,transport::complete,System::currentTimeMillis,transport.timeoutMillis());}
    String version(){return VERSION+":"+RecommendationCpaPayload.VERSION+":"+RecommendationCpaTransport.VERSION+":"+timeoutMillis+":"+(enabled?"enabled":"disabled");}
    RecommendationModelRunner.Advisory run(Snapshot snapshot,List<Decision> selected,Revalidate access)throws Exception {
        return run(snapshot,selected,new RecommendationCpaPayload.ReviewedProjection(Map.of()),access);
    }
    RecommendationModelRunner.Advisory run(Snapshot snapshot,List<Decision> selected,RecommendationCpaPayload.ReviewedProjection reviews,Revalidate access)throws Exception {
        long start=clock.getAsLong();Map<String,Object> audit=baseAudit(snapshot);
        if(!enabled)return result("DISABLED",audit,Map.of());
        if(!slot.tryAcquire())return result("BUSY",audit,Map.of());
        try {
            RecommendationCpaPayload.Prepared prepared;
            try{prepared=RecommendationCpaPayload.prepare(snapshot,selected,reviews);}catch(RecommendationCpaPayload.PrivacyException rejected){audit.put("filter",map("sourceTextCopied",false,"projectionVersion",RecommendationCpaPayload.VERSION,"rejectionStatus",rejected.status()));return result(rejected.status(),audit,Map.of());}
            audit.put("filter",prepared.filter());
            int bytes=Json.write(prepared.request()).getBytes(StandardCharsets.UTF_8).length;
            if(bytes>16384)return result("INPUT_LIMIT",audit,Map.of());
            access.check(); // Fresh session, scope and material check immediately before any possible send.
            String limit=reserve(bytes);if(!"NONE".equals(limit)){audit.put("limitReason",limit);return result("USAGE_LIMIT",audit,Map.of());}
            audit.put("attempts",1);audit.put("inputBytes",bytes);
            try{
                RecommendationCpaTransport.Result response=client.complete(Json.parseMap(Json.write(prepared.request())));
                // Transport audit contains only locally normalized fields; never copy arbitrary model metadata.
                for(String key:List.of("requestedModel","responseModel","usage","elapsedMs","reasoningEffortRequested","reasoningEffortVerified","clientTimeoutMillis","timeoutOrigin","requestTraceId"))if(response.audit().containsKey(key))audit.put(key,response.audit().get(key));
                Map<String,Map<String,Object>> suggestions=validate(response.advice(),prepared);
                audit.put("elapsedMs",Math.max(0,clock.getAsLong()-start));
                return result("COMPLETE",audit,suggestions);
            }catch(RecommendationCpaTransport.SafeFailure failed){for(String key:List.of("requestedModel","responseModel","usage","elapsedMs","reasoningEffortRequested","reasoningEffortVerified","clientTimeoutMillis","timeoutOrigin","requestTraceId"))if(failed.audit.containsKey(key))audit.put(key,failed.audit.get(key));audit.put("elapsedMs",Math.max(0,clock.getAsLong()-start));return result(failed.status,audit,Map.of());}
            catch(IllegalArgumentException invalid){audit.put("elapsedMs",Math.max(0,clock.getAsLong()-start));return result("INVALID_OUTPUT",audit,Map.of());}
            catch(Exception failed){audit.put("elapsedMs",Math.max(0,clock.getAsLong()-start));return result("UNAVAILABLE",audit,Map.of());}
        }finally{slot.release();}
    }
    /** Safe refusal from local authorized recall before any transport or quota reservation. */
    RecommendationModelRunner.Advisory refused(Snapshot snapshot,String status) {
        if(!Set.of("PRIVACY_REJECTED","NO_USABLE_EVIDENCE","NO_CONFIRMED_EXPERIENCE","STALE_CONFIRMED_EXPERIENCE","INPUT_LIMIT").contains(status))throw invalid("本地投影拒绝状态无效");
        Map<String,Object> audit=baseAudit(snapshot);audit.put("filter",map("sourceTextCopied",false,"projectionVersion",RecommendationCpaPayload.VERSION,"rejectionStatus",status));
        return result(enabled?status:"DISABLED",audit,Map.of());
    }
    private Map<String,Object> baseAudit(Snapshot snapshot) {
        return map("advisoryVersion",version(),"ruleVersion",RecommendationRanking.RULE_VERSION,"materialVersion",DispatchConfirmationGuard.opaque(snapshot.snapshotId()),
                "requestedModel","gemini-3.8-flash-high","responseModel",null,"reasoningEffortRequested","high","reasoningEffortVerified",false,
                "usage",null,"clientTimeoutMillis",timeoutMillis,"attempts",0,"filter",map("sourceTextCopied",false),"elapsedMs",0,"tokenHardCap",false,"upstreamCancellationGuaranteed",false);
    }
    /** Process-wide budget for this advisor instance; reading it never reserves or resets total usage. */
    synchronized Map<String,Object> usageBudget() {return usageBudget(true);}
    private synchronized Map<String,Object> usageBudget(boolean includeConcurrent) {
        long now=clock.getAsLong();boolean elapsed=now-window>=60000;int calls=elapsed?0:minuteCalls,bytes=elapsed?0:minuteBytes;
        String blocked=totalCalls>=30?"SERVICE_TOTAL":calls>=6?"MINUTE_REQUESTS":!elapsed&&"MINUTE_BYTES".equals(lastLimit)?"MINUTE_BYTES":includeConcurrent&&slot.availablePermits()==0?"CONCURRENT_REQUEST":"NONE";
        return map("scope","CURRENT_SERVICE_CYCLE","cycleStartedAt",java.time.Instant.ofEpochMilli(cycleStartedAt).toString(),
                "enabled",enabled,"limit",30,"used",totalCalls,"remaining",Math.max(0,30-totalCalls),
                "minuteLimit",6,"minuteRemaining",Math.max(0,6-calls),"minuteByteLimit",65536,"minuteBytesUsed",bytes,"minuteBytesRemaining",Math.max(0,65536-bytes),
                "minuteResetAt",java.time.Instant.ofEpochMilli(elapsed?now:window+60000).toString(),"blockedBy",blocked);
    }
    private synchronized String reserve(int bytes) {
        long now=clock.getAsLong();if(now-window>=60000){window=now;minuteCalls=0;minuteBytes=0;lastLimit="NONE";}
        lastLimit=totalCalls>=30?"SERVICE_TOTAL":minuteCalls>=6?"MINUTE_REQUESTS":minuteBytes+bytes>65536?"MINUTE_BYTES":"NONE";
        if(!"NONE".equals(lastLimit))return lastLimit;
        totalCalls++;minuteCalls++;minuteBytes+=bytes;return "NONE";
    }
    static Map<String,Map<String,Object>> validate(Map<String,Object> raw,RecommendationCpaPayload.Prepared prepared){
        if(raw==null||!raw.keySet().equals(Set.of("suggestions"))||!(raw.get("suggestions") instanceof List<?> list)||list.size()!=prepared.allowed().size()||list.size()>6)throw invalid("AI响应结构无效");
        Map<String,Map<String,Object>> output=new LinkedHashMap<>();Set<String> seen=new HashSet<>();
        for(Object row:list){
            if(!(row instanceof Map<?,?> item)||!item.keySet().equals(Set.of("candidate_id","evidence_ids","reason_codes"))||!(item.get("candidate_id") instanceof String id)||!prepared.allowed().containsKey(id)||!seen.add(id))throw invalid("AI候选编号无效");
            if(!(item.get("evidence_ids") instanceof List<?> ids)||ids.size()>8||!(item.get("reason_codes") instanceof List<?> codes)||codes.size()!=1||!(codes.get(0) instanceof String code)||!Set.of("TOPIC_RELATED","TOPIC_UNCLEAR").contains(code))throw invalid("AI主题提示无效");
            List<String> actual=new ArrayList<>();Set<String> unique=new HashSet<>();String candidate=prepared.candidateAliases().get(id);
            for(Object ref:ids){if(!(ref instanceof String e)||!unique.add(e)||!prepared.allowed().get(id).containsKey(e))throw invalid("AI证据不属于候选");Evidence evidence=prepared.allowed().get(id).get(e);if(!evidence.candidateId().equals(candidate)||!evidence.id().equals(prepared.evidenceAliases().get(e)))throw invalid("AI证据映射无效");actual.add(evidence.id());}
            if(code.equals("TOPIC_RELATED")==actual.isEmpty())throw invalid("AI主题提示与证据不一致");
            List<Map<String,Object>> localEvidence=localEvidence(id,prepared);
            Set<String> usable=new HashSet<>();for(var e:localEvidence)if(usableEvidence(e,prepared))usable.add((String)e.get("evidenceId"));
            if(!usable.containsAll(actual))throw invalid("AI引用缺少本课程结构化经历依据");
            output.put(candidate,map("reasonCodes",List.of(code),"evidenceIds",actual,"localEvidence",localEvidence,"reason",code.equals("TOPIC_RELATED")?"AI选中部分操作人已核事实供比较。下方完整保留本次已核来源中的结构化记录，包括否定、限制与未选中项；操作人确认不等于教师签认、资格认证或适配结论。":"AI未选中明确线索；下方仍保留本次操作人已核结构化记录。未选中或未记录不代表候选没有能力，不能据此确认资格或派课。"));
        }
        if(Json.write(output).getBytes(StandardCharsets.UTF_8).length>65536)throw invalid("AI结果超限");return Collections.unmodifiableMap(output);
    }
    /** All projected evidence is shown, not only model-selected rows: negative facts cannot be hidden by selection. */
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> localEvidence(String alias,RecommendationCpaPayload.Prepared prepared) {
        Map<String,Object> requirement=(Map<String,Object>)prepared.request().get("course_requirement");
        String topic=(String)requirement.get("topic");
        List<Map<String,Object>> rows=List.of();
        for(Object raw:(List<?>)prepared.request().get("candidates")) {var c=(Map<String,Object>)raw;if(alias.equals(c.get("candidate_id"))){rows=(List<Map<String,Object>>)c.get("evidence");break;}}
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:rows){
            String key=(String)row.get("evidence_id");Evidence source=prepared.allowed().get(alias).get(key);
            if(source==null||!source.candidateId().equals(prepared.candidateAliases().get(alias))||!source.id().equals(prepared.evidenceAliases().get(key)))throw invalid("本地结构化证据映射无效");
            List<Map<String,Object>> claims=(List<Map<String,Object>>)row.get("claims");
            if(claims==null||claims.stream().anyMatch(c->!topic.equals(c.get("topic"))||!"STAFF_CONFIRMED".equals(c.get("verification"))))throw invalid("本地已核事实与课程不对应");
            result.add(map("evidenceId",source.id(),"candidateId",source.candidateId(),"kind",source.kind(),"title",source.title(),"sourceRef",source.sourceRef(),"sourceVersion",source.sourceVersion(),"summary",row.get("summary"),"claims",claims,"verification","STAFF_CONFIRMED"));
        }
        return List.copyOf(result);
    }
    @SuppressWarnings("unchecked") private static boolean usableEvidence(Map<String,Object> evidence,RecommendationCpaPayload.Prepared prepared) {
        String topic=(String)((Map<String,Object>)prepared.request().get("course_requirement")).get("topic");
        return ((List<Map<String,Object>>)evidence.get("claims")).stream().anyMatch(c->topic.equals(c.get("topic"))&&"STAFF_CONFIRMED".equals(c.get("verification"))&&!"UNKNOWN".equals(c.get("assertion")));
    }
    private RecommendationModelRunner.Advisory result(String status,Map<String,Object> audit,Map<String,Map<String,Object>> suggestions){
        boolean success=status.equals("COMPLETE");String detail=switch(status){case "DISABLED"->"AI通道已禁用";case "PRIVACY_REJECTED"->"需求或资料无法安全投影为允许的结构化语义，或含指令性文本，已拒绝发送";case "NO_CONFIRMED_EXPERIENCE"->"尚无当前有效且有来源的课程经历确认，本次未调用AI；请在本次人工核对中明确记录，未核原文不发送";case "STALE_CONFIRMED_EXPERIENCE"->"经历确认已失效或来源发生变化，本次未调用AI；请重新核对来源与结构化记录";case "NO_USABLE_EVIDENCE"->"没有可安全使用的本课程结构化经历线索，本次未调用AI；请补充或核对资料，不据此判断候选能力";case "TIMED_OUT"->"AI响应超时；上游仍可能继续处理，本次不自动重试";case "AUTH_REQUIRED"->"本机CPA需要新的鉴权，未读取或配置凭据";case "INVALID_OUTPUT"->"AI响应的结构或候选证据归属未通过校验";case "BUSY"->"已有一次AI请求进行中，本次未排队";case "USAGE_LIMIT"->"已达到本次服务的调用额度";case "INPUT_LIMIT","OUTPUT_LIMIT"->"AI输入或响应超出允许大小";case "RATE_LIMIT"->"本机CPA返回额度或频率限制";case "COMPLETE"->"AI只选择操作人本次已核事实；完整记录供人工比较，不改变资格、交通排序或人工确认";default->"本次AI不可用";};
        Map<String,Object> metadata=new LinkedHashMap<>(audit);Map<String,Object> budget=usageBudget(false);if("BUSY".equals(status))budget.put("blockedBy","CONCURRENT_REQUEST");if("USAGE_LIMIT".equals(status))budget.put("blockedBy",audit.get("limitReason"));metadata.put("usageBudget",budget);metadata.putAll(map("source",success?"AI":"RULES","status",status,"realInference",success,"inferenceSucceeded",success,"provider","LOCAL_CPA","message",success?detail:detail+"；继续显示RULES规则筛选结果。"));
        // Safe bounded audit only: no names, source text, full prompts or upstream response body.
        System.out.println("CPA_ADVISORY "+Json.write(metadata));
        return new RecommendationModelRunner.Advisory(Collections.unmodifiableMap(metadata),suggestions);
    }
}
