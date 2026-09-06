package com.ssafy.a307.estimatevalidation.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record CostComparisonResponse(
        Long accidentId,
        AiEstimate aiEstimate,
        List<ShopEstimate> shopEstimates,
        ActualRepair actualRepair) {

    public record AiEstimate(
            Long estimateId,
            Short version,
            Integer totalMin,
            Integer totalMedian,
            Integer totalMax,
            Long differenceFromActual,
            Boolean actualWithinRange) {
    }

    public record ShopEstimate(
            Long validationId,
            Integer claimedTotal,
            Long differenceFromActual,
            Instant completedAt) {
    }

    public record ActualRepair(
            Integer cost,
            LocalDate completedDate,
            String repairShopName,
            Instant recordedAt) {
    }
}
