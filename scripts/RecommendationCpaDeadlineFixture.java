package com.training;
import java.nio.file.*;
import static com.training.RecommendationDecision.map;
/** Record only non-secret deadline settings; delegate to the same isolated synthetic fixture. */
public final class RecommendationCpaDeadlineFixture {
    public static void main(String[]args)throws Exception {
        Path root=Path.of(System.getProperty("integration.v2.root")).toRealPath();
        if(!root.toString().contains("yanxu-recommendation-v2")||!"127.0.0.1".equals(System.getProperty("bind.address")))throw new IllegalArgumentException("isolated loopback fixture required");
        Main.configureHttpLimits();
        Files.writeString(root.resolve("HTTP_LIMITS.json"),Json.write(map("cpaEnabled",Boolean.getBoolean("recommendation.cpa.enabled"),"clientTimeoutMillis",RecommendationCpaTransport.configuredTimeoutMillis(),"httpMaxResponseSeconds",System.getProperty("sun.net.httpserver.maxRspTime"),"httpMaxRequestSeconds",System.getProperty("sun.net.httpserver.maxReqTime"),"retries",0,"localConcurrency",1)));
        RecommendationV2Fixture.main(args);
    }
}
