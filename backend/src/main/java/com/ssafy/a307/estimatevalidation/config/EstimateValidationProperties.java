package com.ssafy.a307.estimatevalidation.config;

import com.ssafy.a307.estimatevalidation.domain.GradePolicy;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;

@Validated
@ConfigurationProperties(prefix = "app.estimate-validation")
public record EstimateValidationProperties(
        @Min(1) @Max(100) int referencePercentile,
        @NotNull @DecimalMin(value = "1.0", inclusive = false) BigDecimal severeOverP75Multiplier,
        @NotNull @DecimalMin("0.0") BigDecimal cautionTotalDifferenceRatio,
        @NotNull @DecimalMin("0.0") BigDecimal needsReviewTotalDifferenceRatio,
        @Min(1) int needsReviewItemCount,
        @Min(1) @Max(1440) int presignedUrlMinutes
) implements GradePolicy {

    @AssertTrue(message = "needs-review-total-difference-ratio must be at least caution-total-difference-ratio")
    public boolean isDifferenceRatioOrderValid() {
        return cautionTotalDifferenceRatio == null
                || needsReviewTotalDifferenceRatio == null
                || needsReviewTotalDifferenceRatio.compareTo(cautionTotalDifferenceRatio) >= 0;
    }
}
