package com.ssafy.a307.accident.image;

import com.ssafy.a307.accident.entity.AccidentImage;
import com.ssafy.a307.accident.entity.ImageQualityStatus;
import com.ssafy.a307.common.config.ImageQualityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 품질 판정 — {@code ImageQualityProperties} 의 첫 소비자가 그 클래스의 설계 의도대로 도는지 본다.
 */
@DisplayName("이미지 품질 판정")
class ImageQualityAssessorTest {

    private static final int MIN_SHORT_EDGE = 720;
    private static final double BLUR_THRESHOLD = 100.0;

    @Test
    @DisplayName("enabled=false 면 판정하지 않고 PASS 를 유지한다")
    void disabledKeepsPass() {
        ImageQualityAssessor assessor = assessor(false, Optional.empty());

        ImageQualityAssessor.Assessment result = assessor.assess(10, new byte[]{1});

        assertThat(result.status()).isEqualTo(ImageQualityStatus.PASS);
        assertThat(result.reason()).isNull();
    }

    @Test
    @DisplayName("짧은 변이 기준 미만이면 WARN 과 사유를 남긴다")
    void shortEdgeBelowThreshold() {
        ImageQualityAssessor.Assessment result =
                assessor(true, Optional.empty()).assess(MIN_SHORT_EDGE - 1, new byte[]{1});

        assertThat(result.status()).isEqualTo(ImageQualityStatus.WARN);
        assertThat(result.reason())
                .contains(String.valueOf(MIN_SHORT_EDGE - 1))
                .contains(String.valueOf(MIN_SHORT_EDGE));
    }

    @Test
    @DisplayName("기준과 같으면 통과한다 — 경계는 미만부터다")
    void shortEdgeBoundary() {
        assertThat(assessor(true, Optional.empty()).assess(MIN_SHORT_EDGE, new byte[]{1}).status())
                .isEqualTo(ImageQualityStatus.PASS);
    }

    @Test
    @DisplayName("사유는 quality_reason 의 100자 안에 들어간다")
    void reasonFitsColumn() {
        String reason = assessor(true, Optional.empty()).assess(1, new byte[]{1}).reason();

        assertThat(reason.length()).isLessThanOrEqualTo(AccidentImage.MAX_QUALITY_REASON_LENGTH);
    }

    @Test
    @DisplayName("블러 포트가 없으면 블러 판정을 건너뛴다 — 가짜 값으로 판정하지 않는다")
    void blurSkippedWithoutPort() {
        ImageQualityAssessor assessor = assessor(true, Optional.empty());

        assertThat(assessor.blurAssessmentConnected()).isFalse();
        assertThat(assessor.assess(MIN_SHORT_EDGE, new byte[]{1}).status())
                .isEqualTo(ImageQualityStatus.PASS);
    }

    @Test
    @DisplayName("블러 포트가 붙으면 임계값 미만을 WARN 으로 잡는다")
    void blurJudgedWhenPortPresent() {
        ImageQualityAssessor assessor = assessor(true, Optional.of(content -> BLUR_THRESHOLD - 1));

        ImageQualityAssessor.Assessment result = assessor.assess(MIN_SHORT_EDGE, new byte[]{1});

        assertThat(assessor.blurAssessmentConnected()).isTrue();
        assertThat(result.status()).isEqualTo(ImageQualityStatus.WARN);
        assertThat(result.reason()).contains("흔들림");
        assertThat(result.reason().length())
                .isLessThanOrEqualTo(AccidentImage.MAX_QUALITY_REASON_LENGTH);
    }

    @Test
    @DisplayName("블러 임계값 이상이면 통과한다")
    void blurAboveThreshold() {
        ImageQualityAssessor assessor = assessor(true, Optional.of(content -> BLUR_THRESHOLD));

        assertThat(assessor.assess(MIN_SHORT_EDGE, new byte[]{1}).status())
                .isEqualTo(ImageQualityStatus.PASS);
    }

    @Test
    @DisplayName("해상도 미달이 블러보다 먼저다 — 사유가 둘 다 붙지 않는다")
    void resolutionTakesPrecedence() {
        ImageQualityAssessor assessor = assessor(true, Optional.of(content -> 0.0));

        assertThat(assessor.assess(10, new byte[]{1}).reason()).contains("해상도");
    }

    private static ImageQualityAssessor assessor(
            boolean enabled, Optional<ImageBlurVariancePort> blurPort) {
        return new ImageQualityAssessor(
                new ImageQualityProperties(enabled, MIN_SHORT_EDGE, BLUR_THRESHOLD), blurPort);
    }
}
