package com.training;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.training.RecommendationDecision.*;

/** Replaceable transport-reference boundary. Contains no person, candidate, credential or booking action. */
interface RecommendationTravelProvider {
    String id();
    Estimate lookup(Query query) throws Exception;

    record Query(String departurePlace, String teachingPlace, String earliestDeparture, String latestArrival) {
        public Query {
            departurePlace=bounded(departurePlace,200,true); teachingPlace=bounded(teachingPlace,200,true);
            earliestDeparture=bounded(earliestDeparture,40,true); latestArrival=bounded(latestArrival,40,true);
            try {
                OffsetDateTime from=earliestDeparture.isBlank()?null:OffsetDateTime.parse(earliestDeparture);
                OffsetDateTime to=latestArrival.isBlank()?null:OffsetDateTime.parse(latestArrival);
                if(from!=null && to!=null && (to.isBefore(from) || Duration.between(from,to).toDays()>14)) throw invalid("交通时间窗口无效");
            } catch(java.time.format.DateTimeParseException malformed) { throw invalid("交通时间须含明确日期、时间和时区"); }
        }
        List<String> missing() {
            List<String> items=new ArrayList<>();
            if(departurePlace.isBlank()) items.add("明确出发地"); if(teachingPlace.isBlank()) items.add("明确授课地点");
            if(earliestDeparture.isBlank()) items.add("最早出发时间（含时区）"); if(latestArrival.isBlank()) items.add("最晚到达时间（含时区）");
            return List.copyOf(items);
        }
    }
    /** Estimated journey figures are never seat inventory or proof that a candidate can attend. */
    record Estimate(String source, String queriedAt, Integer durationMinutes, BigDecimal estimatedCost, String currency,
                    List<String> uncertainty, List<String> missingInfo, boolean synthetic) {
        public Estimate {
            source=bounded(source,120,false); queriedAt=bounded(queriedAt,40,false); Instant.parse(queriedAt);
            currency=bounded(currency,3,true);
            if(durationMinutes!=null && (durationMinutes<1 || durationMinutes>20160)) throw invalid("交通时长超出范围");
            if(estimatedCost!=null && (estimatedCost.signum()<0 || estimatedCost.compareTo(new BigDecimal("1000000"))>0 || !currency.matches("[A-Z]{3}"))) throw invalid("交通费用估算无效");
            if(estimatedCost==null && !currency.isEmpty()) throw invalid("未知费用不得虚设币种");
            uncertainty=List.copyOf(uncertainty); missingInfo=List.copyOf(missingInfo);
            if(uncertainty.size()>12 || missingInfo.size()>12) throw invalid("交通说明过多");
            for(String text:uncertainty) bounded(text,300,false); for(String text:missingInfo) bounded(text,300,false);
        }
        Map<String,Object> value() {
            return map("status",missingInfo.isEmpty()?(synthetic?"SYNTHETIC_ESTIMATE":"ESTIMATE"):"INSUFFICIENT_INPUT","source",source,"queriedAt",queriedAt,"durationMinutes",durationMinutes,"estimatedCost",estimatedCost,"currency",currency,
                    "uncertainty",uncertainty,"missingInfo",missingInfo,"synthetic",synthetic,"kind","ESTIMATE_ONLY",
                    "ticketAvailability","NOT_QUERIED","purchasableTicket",false,"candidateExclusion",false,"bookingCreated",false);
        }
    }
    /** Only test fixtures instantiate this provider; production host does not call transport services. */
    final class MockProvider implements RecommendationTravelProvider {
        private final Clock clock;
        MockProvider(Clock clock) { this.clock=Objects.requireNonNull(clock); }
        public String id() { return "MOCK_TRAVEL"; }
        public Estimate lookup(Query query) {
            List<String> missing=query.missing();
            return new Estimate("MOCK_TRAVEL_SYNTHETIC",clock.instant().toString(),missing.isEmpty()?120:null,
                    missing.isEmpty()?new BigDecimal("200.00"):null,missing.isEmpty()?"CNY":"",
                    List.of("合成测试估算，不代表真实路线、实时价格或可购余票", "未核对市内接驳、换乘和前后行程，不能据此排除候选"),missing,true);
        }
    }
}
