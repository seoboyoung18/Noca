package com.ssafy.a307.estimatevalidation.dto;

import java.time.Instant;
import java.time.LocalDate;

public record ModelImprovementRecord(
        String recordKey,
        Long accidentId,
        String carClass,
        Long estimateId,
        Short estimateVersion,
        Integer aiTotalMin,
        Integer aiTotalMedian,
        Integer aiTotalMax,
        Long validationId,
        Integer shopClaimedTotal,
        Integer actualRepairCost,
        LocalDate actualRepairCompletedDate,
        Instant actualCostRecordedAt) {
}
