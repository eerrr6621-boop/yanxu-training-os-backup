package com.training;

import java.nio.file.*;
import java.util.*;

/** Pure synthetic checks for the pure-rule startup guard: no listener, database or network. The startup part calls the
 * real Main.start with a model switch set and proves it stops before creating the data directory or any listener. */
public final class PureRulePolicyTest {
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static List<String> channels(Map<String,String> properties, Map<String,String> environment) {
        return PureRulePolicy.enabledChannels(properties::get, environment::get);
    }

    private static void refusedAtStartup(String key, String value) throws Exception {
        Path dir = Files.createTempDirectory("yanxu-pure-rule-").resolve("data-never-created");
        System.setProperty("data.dir", dir.toString()); System.setProperty(key, value);
        try { Main.start(new String[]{"0"}, null); check(false, "startup must refuse " + key); }
        catch (IllegalStateException refused) { check(refused.getMessage().contains(key), "startup refusal names " + key); }
        finally { System.clearProperty(key); System.clearProperty("data.dir"); }
        check(!Files.exists(dir), "refused before any database work: " + key);
    }

    public static void main(String[] ignored) throws Exception {
        check(channels(Map.of(), Map.of()).isEmpty(), "nothing configured starts");
        check(channels(Map.of("recommendation.cpa.enabled", "false"), Map.of()).isEmpty(), "explicit false starts");
        check(channels(Map.of("semantic.port", "", "semantic.token", ""), Map.of("YANXU_SEMANTIC_PORT", "18080")).isEmpty(),
                "empty property overrides environment, as LocalSemantic reads it");
        check(channels(Map.of("recommendation.cpa.enabled", "TRUE"), Map.of()).contains("recommendation.cpa.enabled"), "CPA switch refused");
        check(channels(Map.of("semantic.port", "18080"), Map.of()).size() == 1, "semantic port property refused");
        check(channels(Map.of(), Map.of("YANXU_SEMANTIC_PORT", "18080", "YANXU_SEMANTIC_TOKEN", "x".repeat(32))).size() == 2, "semantic environment refused");
        check(PureRulePolicy.ADVICE_DISABLED_MESSAGE.contains("规则"), "advice refusal explains the pure-rule policy");
        refusedAtStartup("recommendation.cpa.enabled", "true");
        refusedAtStartup("semantic.port", "18080");
        refusedAtStartup("semantic.token", "x".repeat(32));
        System.out.println("PureRulePolicyTest: " + checks + " checks passed; synthetic only, no listener/database/network.");
    }
}
