package com.ssafy.a307.estimatevalidation.domain;

public final class GradeDecider {

    public ValidationGrade decide(GradeAssessment assessment, GradePolicy policy) {
        if (assessment.reviewItemCount() >= policy.needsReviewItemCount()
                || assessment.highestReferenceRatio().compareTo(policy.severeOverP75Multiplier()) >= 0
                || assessment.totalDifferenceRatio().compareTo(policy.needsReviewTotalDifferenceRatio()) >= 0) {
            return ValidationGrade.NEEDS_REVIEW;
        }
        if (assessment.reviewItemCount() > 0
                || assessment.totalDifferenceRatio().compareTo(policy.cautionTotalDifferenceRatio()) >= 0) {
            return ValidationGrade.CAUTION;
        }
        return ValidationGrade.APPROPRIATE;
    }
}
