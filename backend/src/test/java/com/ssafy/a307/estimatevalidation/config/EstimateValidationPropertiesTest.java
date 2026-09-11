package com.ssafy.a307.estimatevalidation.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 견적서 검증 <b>인프라 설정</b> 바인딩.
 *
 * <h2>판정 임계값 테스트가 여기서 빠진 이유</h2>
 * 이 클래스는 원래 등급 임계값 다섯 개가 프로퍼티에서 바인딩되는지, 잘못된 값이면 기동이
 * 실패하는지를 봤다. 그 값들은 {@code estimate_validation_rule} 테이블로 옮겨졌다 —
 * 재배포 없이 바꿀 수 있어야 하고, 과거 판정의 근거가 남아야 하기 때문이다.
 *
 * <p><b>검증이 사라진 것이 아니라 자리를 옮겼다.</b> 같은 규칙을 이제 세 곳이 지킨다.
 * <ul>
 *   <li>{@code EstimateValidationRuleUpdateRequest} — 요청 단계의 Bean Validation 과 교차 필드 검사</li>
 *   <li>{@code EstimateValidationRule.nextVersion} — 어느 경로로 들어와도 깨진 값은 저장되지 않는 불변식</li>
 *   <li>정본 DDL 의 {@code ck_evr_*} CHECK — 마지막 방어선</li>
 * </ul>
 *
 * <p>아래 {@link #thresholdsAreNoLongerProperties()} 는 <b>그 값들이 프로퍼티로 되돌아오지
 * 않는지</b> 지킨다. 되돌아오면 DB 와 프로퍼티라는 두 공급원이 생겨 어느 쪽이 적용됐는지
 * 알 수 없게 된다.
 */
@DisplayName("견적서 검증 인프라 설정")
class EstimateValidationPropertiesTest {

    /** 규칙 테이블로 옮겨 간 값들. 프로퍼티로 돌아오면 안 된다. */
    private static final List<String> MOVED_TO_DATABASE = List.of(
            "referencePercentile",
            "severeOverP75Multiplier",
            "cautionTotalDifferenceRatio",
            "needsReviewTotalDifferenceRatio",
            "needsReviewItemCount");

    @EnableConfigurationProperties(EstimateValidationProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues("app.estimate-validation.presigned-url-minutes=10");

    @Test
    @DisplayName("presigned URL 유효시간이 바인딩된다")
    void bindsPresignedUrlMinutes() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EstimateValidationProperties.class).presignedUrlMinutes())
                    .isEqualTo(10);
        });
    }

    @Test
    @DisplayName("유효시간이 빠지거나 범위를 벗어나면 기동이 실패한다 — 코드에 기본값을 두지 않았다")
    void rejectsInvalidPresignedUrlMinutes() {
        new ApplicationContextRunner().withUserConfiguration(TestConfig.class)
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.estimate-validation.presigned-url-minutes=0")
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues("app.estimate-validation.presigned-url-minutes=1441")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("판정 임계값은 프로퍼티에 없다 — 공급원이 둘이 되면 어느 쪽이 적용됐는지 알 수 없다")
    void thresholdsAreNoLongerProperties() {
        List<String> components = Arrays.stream(EstimateValidationProperties.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(components)
                .as("판정 임계값은 estimate_validation_rule 테이블이 정본이다")
                .doesNotContainAnyElementsOf(MOVED_TO_DATABASE);
        assertThat(components).containsExactly("presignedUrlMinutes");
    }

    @Test
    @DisplayName("GradePolicy 를 더 이상 구현하지 않는다 — 판정 규칙 공급원은 DB 엔티티다")
    void isNoLongerGradePolicy() {
        assertThat(com.ssafy.a307.estimatevalidation.domain.GradePolicy.class
                .isAssignableFrom(EstimateValidationProperties.class))
                .as("설정 클래스가 판정 정책을 겸하면 DB 규칙과 공급원이 갈린다")
                .isFalse();
        assertThat(com.ssafy.a307.estimatevalidation.domain.GradePolicy.class
                .isAssignableFrom(com.ssafy.a307.estimatevalidation.entity.EstimateValidationRule.class))
                .as("판정 정책은 규칙 엔티티가 구현한다")
                .isTrue();
    }
}
