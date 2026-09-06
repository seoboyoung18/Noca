package com.ssafy.a307.estimatevalidation.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class EstimateValidationPropertiesTest {

    @EnableConfigurationProperties(EstimateValidationProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues(validProperties());

    @Test
    void bindsExternalizedThresholds() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            EstimateValidationProperties properties = context.getBean(EstimateValidationProperties.class);
            assertThat(properties.referencePercentile()).isEqualTo(75);
            assertThat(properties.severeOverP75Multiplier()).isEqualByComparingTo(new BigDecimal("1.5"));
            assertThat(properties.cautionTotalDifferenceRatio()).isEqualByComparingTo(new BigDecimal("0.10"));
            assertThat(properties.needsReviewTotalDifferenceRatio()).isEqualByComparingTo(new BigDecimal("0.20"));
            assertThat(properties.needsReviewItemCount()).isEqualTo(3);
            assertThat(properties.presignedUrlMinutes()).isEqualTo(10);
        });
    }

    @Test
    void rejectsInvalidAndReversedThresholdsAtStartup() {
        runner.withPropertyValues("app.estimate-validation.reference-percentile=101")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.estimate-validation.severe-over-p75-multiplier=1.0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.estimate-validation.caution-total-difference-ratio=0.30")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsMissingDecimalThresholds() {
        new ApplicationContextRunner().withUserConfiguration(TestConfig.class)
                .withPropertyValues("app.estimate-validation.reference-percentile=75",
                        "app.estimate-validation.needs-review-item-count=3")
                .run(context -> assertThat(context).hasFailed());
    }

    private static String[] validProperties() {
        return new String[]{
                "app.estimate-validation.reference-percentile=75",
                "app.estimate-validation.severe-over-p75-multiplier=1.5",
                "app.estimate-validation.caution-total-difference-ratio=0.10",
                "app.estimate-validation.needs-review-total-difference-ratio=0.20",
                "app.estimate-validation.needs-review-item-count=3",
                "app.estimate-validation.presigned-url-minutes=10"
        };
    }
}
