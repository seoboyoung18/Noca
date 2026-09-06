package com.ssafy.a307.estimatevalidation.dto;

import com.ssafy.a307.estimatevalidation.domain.ValidationFlag;
import com.ssafy.a307.estimatevalidation.domain.ValidationGrade;
import com.ssafy.a307.estimatevalidation.domain.ValidationStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ValidationResultResponse(
        Long validationId,
        Long accidentId,
        Long estimateId,
        ValidationStatus status,
        ValidationGrade grade,
        String gradeDisplayName,
        String summary,
        Integer claimedTotal,
        Integer aiTotalMin,
        Integer aiTotalMedian,
        Integer aiTotalMax,
        Long differenceFromMedian,
        Long differenceFromRangeMax,
        int reviewItemCount,
        int totalItemCount,
        List<Item> items,
        List<ValidationQuestionResponse> questions,
        Integer actualRepairCost,
        LocalDate actualRepairCompletedDate,
        String repairShopName,
        Long shopEstimateDifferenceFromActual,
        Long aiMedianDifferenceFromActual,
        Boolean actualWithinAiRange,
        String legalNotice,
        Instant createdAt,
        Instant completedAt) {

    public record Item(
            short lineNo,
            String rawItemName,
            String normalizedName,
            String partCode,
            String workType,
            short quantity,
            Integer partCost,
            Integer laborCost,
            Integer subtotal,
            Integer referenceMin,
            Integer referenceMedian,
            Integer referenceP75,
            Integer referenceMax,
            Integer referenceCaseCount,
            ValidationFlag flag,
            String reason,
            String displayDecision) {
    }
}
