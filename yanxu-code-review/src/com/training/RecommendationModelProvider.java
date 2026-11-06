package com.training;

import java.util.*;

/** Server-selected port. No HTTP, SDK, OAuth, key lookup or automatic provider discovery. */
interface RecommendationModelProvider {
    String id();
    boolean modelVisible();
    boolean syntheticOnly();
    Map<String,Object> infer(Map<String,Object> request) throws Exception;
    final class TransientFailure extends Exception { private static final long serialVersionUID=1L; TransientFailure() { super("模型临时不可用"); } }

    /** Local synthetic stub. The runner refuses it for non-synthetic snapshots. */
    final class MockProvider implements RecommendationModelProvider {
        public String id() { return "MOCK"; }
        public boolean modelVisible() { return true; }
        public boolean syntheticOnly() { return true; }
        public Map<String,Object> infer(Map<String,Object> request) {
            List<Object> output = new ArrayList<>();
            for (Object raw : (List<?>) request.get("candidates")) {
                Map<?,?> c = (Map<?,?>) raw; List<?> evidence = (List<?>) c.get("evidence");
                List<String> ids = new ArrayList<>();
                for (Object item : evidence) { Map<?,?> e = (Map<?,?>) item; if (RecommendationDecision.matchesTopic((String)e.get("text"),(String)request.get("topic"))) ids.add((String)e.get("evidence_id")); }
                if (ids.size() > 8) ids = ids.subList(0,8);
                output.add(RecommendationDecision.map("candidate_id", c.get("candidate_id"), "evidence_ids", ids,
                        "reason_codes", List.of(ids.isEmpty() ? "TOPIC_UNCLEAR" : "TOPIC_RELATED")));
            }
            return RecommendationDecision.map("suggestions", output);
        }
    }
}
