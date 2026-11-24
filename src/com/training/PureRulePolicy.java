package com.training;

import java.util.*;
import java.util.function.Function;

/**
 * Deployment guard for the confirmed pure-rule policy: teacher recommendation never uses a model.
 * The optional CPA advisor and the local semantic worker stay in the source tree (not deleted),
 * but a server started with either switch configured refuses to start instead of silently enabling it.
 */
final class PureRulePolicy {
    private PureRulePolicy() {}

    static final String ADVICE_DISABLED_MESSAGE = "AI 辅助已停用：本系统只使用规则推荐，不调用任何模型。";

    /** Called once at startup, before any listener or database work. */
    static void requireNoModelChannels() {
        List<String> enabled = enabledChannels(System::getProperty, System::getenv);
        if (!enabled.isEmpty())
            throw new IllegalStateException("纯规则部署不允许启用模型通道，请移除以下启动配置后再启动：" + String.join("、", enabled));
    }

    /** Mirrors how RecommendationCpaAdvisor and LocalSemantic read their switches; any configured value is refused. */
    static List<String> enabledChannels(Function<String, String> property, Function<String, String> environment) {
        List<String> enabled = new ArrayList<>();
        if ("true".equalsIgnoreCase(Objects.toString(property.apply("recommendation.cpa.enabled"), "").strip()))
            enabled.add("recommendation.cpa.enabled");
        if (!setting(property, environment, "semantic.port", "YANXU_SEMANTIC_PORT").isEmpty())
            enabled.add("semantic.port / YANXU_SEMANTIC_PORT");
        if (!setting(property, environment, "semantic.token", "YANXU_SEMANTIC_TOKEN").isEmpty())
            enabled.add("semantic.token / YANXU_SEMANTIC_TOKEN");
        return List.copyOf(enabled);
    }

    private static String setting(Function<String, String> property, Function<String, String> environment, String key, String variable) {
        String value = property.apply(key);
        if (value == null) value = environment.apply(variable);
        return value == null ? "" : value.strip();
    }
}
