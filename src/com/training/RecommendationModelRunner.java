package com.training;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import static com.training.RecommendationDecision.*;

/** Bounded optional advisory work; rule facts and missing information never enter provider control. */
final class RecommendationModelRunner implements AutoCloseable {
    record Limits(int timeoutMillis, int retries, int concurrency, int inputBytes, int outputBytes, int callsPerMinute, int bytesPerMinute) {
        Limits {
            if (timeoutMillis < 10 || timeoutMillis > 10000 || retries < 0 || retries > 1 || concurrency < 1 || concurrency > 2 || inputBytes < 128 || inputBytes > 262144 || outputBytes < 64 || outputBytes > 131072 || callsPerMinute < 1 || callsPerMinute > 60 || bytesPerMinute < inputBytes || bytesPerMinute > 4194304) throw invalid("模型资源上限无效");
        }
        static Limits defaults() { return new Limits(2000, 1, 1, 262144, 131072, 10, 1048576); }
    }
    record Advisory(Map<String,Object> metadata, Map<String,Map<String,Object>> suggestions) {}
    private static final class Usage { long window; int calls, bytes; Usage(long now) { window=now; } }
    private final RecommendationModelProvider provider;
    private final Limits limits;
    private final LongSupplier clock;
    private final ThreadPoolExecutor executor;
    private final Map<String,Usage> usage = new LinkedHashMap<>();
    private volatile boolean closed;
    RecommendationModelRunner() { this(null, Limits.defaults(), System::currentTimeMillis); }
    RecommendationModelRunner(RecommendationModelProvider provider, Limits limits, LongSupplier clock) {
        this.provider=provider; this.limits=Objects.requireNonNull(limits); this.clock=Objects.requireNonNull(clock);
        // No arbitrary provider or destination is accepted in the current implementation.
        if (provider != null && (!"MOCK".equals(provider.id()) || !provider.syntheticOnly())) throw invalid("本轮仅允许合成Mock模型，其他供应商未接入");
        executor = provider == null ? null : new ThreadPoolExecutor(limits.concurrency(), limits.concurrency(), 10, TimeUnit.SECONDS,
                new SynchronousQueue<>(), work -> { Thread t=new Thread(work,"yanxu-recommendation-mock"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
        if (executor != null) executor.allowCoreThreadTimeOut(true);
    }
    /** V2 explains only an exact bounded shortlist; local ranking remains outside model control. */
    Advisory runV2(Snapshot snapshot,List<Decision> decisions,List<Long> shortlist) {
        if(shortlist.size()>6||new HashSet<>(shortlist).size()!=shortlist.size())return fallback("SHORTLIST_LIMIT",false);
        Map<Long,Decision> indexed=new HashMap<>();for(Decision decision:decisions)indexed.put(decision.candidate().teacherId(),decision);
        List<Decision> selected=new ArrayList<>();for(Long id:shortlist){Decision decision=indexed.get(id);if(decision==null||decision.state()==State.EXCLUDED)return fallback("SHORTLIST_INVALID",false);selected.add(decision);}
        Snapshot limited=new Snapshot(snapshot.organizationCode(),snapshot.snapshotId(),snapshot.request(),selected.stream().map(Decision::candidate).toList(),snapshot.synthetic());
        return run(limited,selected);
    }
    Advisory run(Snapshot snapshot, List<Decision> decisions) {
        if (closed) return fallback("CLOSED", false);
        if (provider == null) return fallback("DISABLED", false);
        if (!snapshot.synthetic()) return fallback("SYNTHETIC_ONLY", false);
        AtomicBoolean visible=new AtomicBoolean(false);
        List<Map<String,Object>> candidates=new ArrayList<>(); Map<String,Map<String,Evidence>> allowed=new LinkedHashMap<>();
        int estimatedInput=bytes(snapshot.request().topic())+bytes(snapshot.organizationCode())+256;
        for (Decision d : decisions) {
            if (d.state()==State.EXCLUDED) continue;
            estimatedInput+=bytes(d.candidate().id())+64;
            Map<String,Evidence> owned=new LinkedHashMap<>(); List<Map<String,Object>> texts=new ArrayList<>();
            for (Evidence e:d.candidate().evidence()) {
                Map<String,Object> text=map("evidence_id",e.id(),"kind",e.kind(),"text",e.text());
                estimatedInput+=bytes(text)+1;
                if(estimatedInput>limits.inputBytes()) return fallback("INPUT_LIMIT",false);
                owned.put(e.id(),e); texts.add(text);
            }
            allowed.put(d.candidate().id(),owned); candidates.add(map("candidate_id",d.candidate().id(),"evidence",texts));
        }
        if (candidates.isEmpty()) return fallback("NO_CANDIDATES", false);
        Map<String,Object> request=map("schema_version","topic_advisory_v1","organization_code",snapshot.organizationCode(),"snapshot_id",snapshot.snapshotId(),"topic",snapshot.request().topic(),"candidates",candidates);
        int bytes=bytes(request);
        if(bytes>limits.inputBytes()) return fallback("INPUT_LIMIT",false);
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(limits.timeoutMillis());
        for(int attempt=0;attempt<=limits.retries();attempt++) {
            long remaining=deadline-System.nanoTime();
            if(remaining<=0) return fallback("TIMED_OUT",visible.get());
            if(!reserve(snapshot.organizationCode(),bytes)) return fallback("USAGE_LIMIT",visible.get());
            Future<Map<String,Object>> future;
            try {
                // The provider receives an isolated JSON copy; it cannot mutate server rules or evidence.
                Map<String,Object> copy=Json.parseMap(Json.write(request));
                future=executor.submit(()-> { visible.set(provider.modelVisible()); return visible.get()?provider.infer(copy):null; });
            } catch(RejectedExecutionException busy) { return fallback("BUSY",visible.get()); }
            try {
                Map<String,Object> raw=future.get(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                if(!visible.get()) return fallback("MODEL_NOT_VISIBLE",false);
                Map<String,Map<String,Object>> validated=validate(raw,allowed,limits.outputBytes());
                return new Advisory(map("source","MOCK","status","COMPLETE","modelVisible",visible.get(),"inferenceSucceeded",true,
                        "realInference",false,"provider","MOCK","attempts",attempt+1,"message","合成Mock主题建议；非真实AI推理，资格与缺失项保持规则结果"),validated);
            } catch(TimeoutException timeout) {
                future.cancel(true); // Non-cooperative work keeps its worker occupied; no queue or replacement thread growth.
                return fallback("TIMED_OUT",visible.get());
            } catch(InterruptedException cancelled) {
                future.cancel(true); Thread.currentThread().interrupt(); return fallback("CANCELLED",visible.get());
            } catch(ExecutionException failure) {
                if(!(failure.getCause() instanceof RecommendationModelProvider.TransientFailure) || attempt==limits.retries()) return fallback("UNAVAILABLE",visible.get());
            } catch(RuntimeException invalidOutput) { return fallback("INVALID_OUTPUT",visible.get()); }
        }
        return fallback("UNAVAILABLE",visible.get());
    }
    private synchronized boolean reserve(String org,int input) {
        long now=clock.getAsLong(); usage.entrySet().removeIf(e->now-e.getValue().window>=60000);
        Usage current=usage.get(org);
        if(current==null) { if(usage.size()>=128)return false; current=new Usage(now);usage.put(org,current); }
        if(current.calls>=limits.callsPerMinute() || input>limits.bytesPerMinute()-current.bytes)return false;
        current.calls++;current.bytes+=input;return true;
    }
    static Map<String,Map<String,Object>> validate(Map<String,Object> raw,Map<String,Map<String,Evidence>> allowed,int maxBytes) {
        if(raw==null || !raw.keySet().equals(Set.of("suggestions")) || !(raw.get("suggestions") instanceof List<?> list) || list.size()!=allowed.size() || list.size()>500)throw invalid("模型输出结构或大小无效");
        Map<String,Map<String,Object>> result=new LinkedHashMap<>();
        for(Object item:list) {
            if(!(item instanceof Map<?,?> m) || !m.keySet().equals(Set.of("candidate_id","evidence_ids","reason_codes")) || !(m.get("candidate_id") instanceof String id) || id.length()>24 || !allowed.containsKey(id) || result.containsKey(id))throw invalid("模型候选标识无效");
            if(!(m.get("evidence_ids") instanceof List<?> ids) || ids.size()>8 || !(m.get("reason_codes") instanceof List<?> reasons) || reasons.size()!=1 || !(reasons.get(0) instanceof String reason) || reason.length()>32 || !Set.of("TOPIC_RELATED","TOPIC_UNCLEAR").contains(reason))throw invalid("模型理由无效");
            List<String> checked=new ArrayList<>();
            for(Object ref:ids) {
                if(!(ref instanceof String evidenceId) || evidenceId.length()!=67 || checked.contains(evidenceId) || !allowed.get(id).containsKey(evidenceId))throw invalid("模型证据不属于该候选");
                Evidence evidence=allowed.get(id).get(evidenceId);
                if(!evidence.candidateId().equals(id) || !Set.of("PROFILE_TOPIC","HISTORY_TOPIC").contains(evidence.kind()))throw invalid("模型证据类型无效");
                checked.add(evidenceId);
            }
            if(reason.equals("TOPIC_RELATED") && checked.isEmpty() || reason.equals("TOPIC_UNCLEAR") && !checked.isEmpty())throw invalid("模型理由与证据不一致");
            result.put(id,Collections.unmodifiableMap(map("reasonCodes",List.of(reason),"evidenceIds",List.copyOf(checked),
                    "reason",reason.equals("TOPIC_RELATED") ? "合成模型建议核对这些资料的主题相关性；不证明资格、授课经历或可用档期。" : "合成模型未形成明确主题建议；不代表候选没有能力。")));
        }
        // Serialize only the normalized, type-checked shallow structure; never arbitrary provider objects.
        if(bytes(result)>maxBytes)throw invalid("模型输出超出大小上限");
        return Collections.unmodifiableMap(result);
    }
    private static int bytes(Object value) { return Json.write(value).getBytes(StandardCharsets.UTF_8).length; }
    private static Advisory fallback(String status,boolean visible) {
        return new Advisory(map("source","RULES","status",status,"modelVisible",visible,"inferenceSucceeded",false,"realInference",false,
                "message","本次显示规则筛选结果；没有AI推理结论，最终人选由人工确认"),Map.of());
    }
    @Override public void close() { closed=true;if(executor!=null)executor.shutdownNow();synchronized(this){usage.clear();} }
}
