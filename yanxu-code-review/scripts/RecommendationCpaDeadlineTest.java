package com.training;

/** Pure property/configuration checks. No server, HTTP connection, credentials or model call. */
public final class RecommendationCpaDeadlineTest {
    static int checks;
    static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    static void clear(){System.clearProperty("sun.net.httpserver.maxRspTime");System.clearProperty("recommendation.cpa.timeoutMillis");System.clearProperty("recommendation.cpa.enabled");}
    public static void main(String[]args){
        try{
            clear();Main.configureHttpLimits();ok(System.getProperty("sun.net.httpserver.maxRspTime").equals("30"),"disabled keeps existing30s");
            clear();System.setProperty("recommendation.cpa.timeoutMillis","invalid");Main.configureHttpLimits();ok(RecommendationCpaAdvisor.configured().version().endsWith(":disabled"),"disabled channel ignores unused invalid CPA timeout and preserves RULES");
            clear();System.setProperty("recommendation.cpa.enabled","true");Main.configureHttpLimits();ok(System.getProperty("sun.net.httpserver.maxRspTime").equals("60"),"enabled45s gets bounded60s outer response");
            clear();System.setProperty("recommendation.cpa.enabled","true");System.setProperty("recommendation.cpa.timeoutMillis","10000");Main.configureHttpLimits();ok(System.getProperty("sun.net.httpserver.maxRspTime").equals("30"),"short configured CPA keeps30s server");
            clear();System.setProperty("recommendation.cpa.enabled","true");System.setProperty("sun.net.httpserver.maxRspTime","30");try{Main.configureHttpLimits();throw new AssertionError("short server allowed");}catch(IllegalArgumentException expected){ok(System.getProperty("sun.net.httpserver.maxRspTime").equals("30"),"explicit short response timeout rejected without override");}
            clear();System.setProperty("recommendation.cpa.enabled","true");System.setProperty("sun.net.httpserver.maxRspTime","60");Main.configureHttpLimits();ok(System.getProperty("sun.net.httpserver.maxRspTime").equals("60"),"explicit sufficient existing timeout preserved");
            for(String value:new String[]{"0","-1","abc","9999999999999999999"}){clear();System.setProperty("recommendation.cpa.enabled","true");System.setProperty("sun.net.httpserver.maxRspTime",value);try{Main.configureHttpLimits();throw new AssertionError("invalid response limit accepted");}catch(IllegalArgumentException expected){ok(true,"invalid explicit response bound rejected");}}
            ok(System.getProperty("sun.net.httpserver.maxReqTime").equals("15"),"request body limit unchanged");
            System.out.println("RecommendationCpaDeadlineTest: "+checks+" checks passed; configuration only; no listener.");
        }finally{clear();}
    }
}
