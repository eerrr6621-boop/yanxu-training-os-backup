package com.training;

/** Backward-compatible test entry: raw-text inference expectations were replaced by confirmed-review boundaries. */
public final class RecommendationCpaHostTest {
    public static void main(String[] args)throws Exception {
        RecommendationConfirmedExperienceHostTest.main(args);
        RecommendationCpaSemanticAdvisorTest.main(new String[0]);
    }
}
