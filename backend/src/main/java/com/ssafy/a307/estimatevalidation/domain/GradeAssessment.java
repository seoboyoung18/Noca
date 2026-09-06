package com.ssafy.a307.estimatevalidation.domain;

import java.math.BigDecimal;

public record GradeAssessment(
        int reviewItemCount,
        BigDecimal highestReferenceRatio,
        BigDecimal totalDifferenceRatio
) {
    public GradeAssessment {
        if (reviewItemCount < 0) throw new IllegalArgumentException("reviewItemCount must not be negative");
        highestReferenceRatio = nonNegative(highestReferenceRatio, "highestReferenceRatio");
        totalDifferenceRatio = nonNegative(totalDifferenceRatio, "totalDifferenceRatio");
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        if (value == null || value.signum() < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }
}
