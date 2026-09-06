package com.ssafy.a307.estimatevalidation.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class GradeDeciderTest {

    private static final GradePolicy POLICY = new GradePolicy() {
        public BigDecimal severeOverP75Multiplier() { return new BigDecimal("1.50"); }
        public BigDecimal cautionTotalDifferenceRatio() { return new BigDecimal("0.10"); }
        public BigDecimal needsReviewTotalDifferenceRatio() { return new BigDecimal("0.20"); }
        public int needsReviewItemCount() { return 3; }
    };
    private final GradeDecider decider = new GradeDecider();

    @Test
    void totalDifferenceBoundariesAreInclusive() {
        assertThat(decide(0, "1.00", "0.0999")).isEqualTo(ValidationGrade.APPROPRIATE);
        assertThat(decide(0, "1.00", "0.10")).isEqualTo(ValidationGrade.CAUTION);
        assertThat(decide(0, "1.00", "0.1999")).isEqualTo(ValidationGrade.CAUTION);
        assertThat(decide(0, "1.00", "0.20")).isEqualTo(ValidationGrade.NEEDS_REVIEW);
        assertThat(decide(0, "1.00", "0.2001")).isEqualTo(ValidationGrade.NEEDS_REVIEW);
    }

    @Test
    void severeReferenceRatioBoundaryIsInclusive() {
        assertThat(decide(1, "1.4999", "0")).isEqualTo(ValidationGrade.CAUTION);
        assertThat(decide(1, "1.50", "0")).isEqualTo(ValidationGrade.NEEDS_REVIEW);
        assertThat(decide(1, "1.5001", "0")).isEqualTo(ValidationGrade.NEEDS_REVIEW);
    }

    @Test
    void reviewCountBoundaryIsInclusive() {
        assertThat(decide(2, "1.00", "0")).isEqualTo(ValidationGrade.CAUTION);
        assertThat(decide(3, "1.00", "0")).isEqualTo(ValidationGrade.NEEDS_REVIEW);
        assertThat(decide(4, "1.00", "0")).isEqualTo(ValidationGrade.NEEDS_REVIEW);
    }

    private ValidationGrade decide(int count, String referenceRatio, String differenceRatio) {
        return decider.decide(new GradeAssessment(count,
                new BigDecimal(referenceRatio), new BigDecimal(differenceRatio)), POLICY);
    }
}
