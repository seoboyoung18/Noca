package com.ssafy.a307.estimatevalidation.domain;

import java.math.BigDecimal;

public interface GradePolicy {
    BigDecimal severeOverP75Multiplier();
    BigDecimal cautionTotalDifferenceRatio();
    BigDecimal needsReviewTotalDifferenceRatio();
    int needsReviewItemCount();
}
