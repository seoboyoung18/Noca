package com.ssafy.a307.estimate.domain;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 견적 항목을 "낮은 신뢰도" 로 볼 기준 (S15P21A307-205 · S15P21A307-291).
 *
 * <p>{@code estimate_item.is_low_confidence} 를 정하는 규칙이다. 화면은 이 값이 참인 항목에
 * 경고를 붙인다.
 *
 * <h2>왜 설정으로 빼나</h2>
 *
 * <p>두 값 모두 <b>실제 사례 분포를 보고 조정해야 하는 값</b>이다. 저장소 어디에도 근거가
 * 없어 지금은 임의값이고, 코드에 박으면 조정할 때마다 코드 리뷰가 필요해진다.
 *
 * <p>DB 테이블이 아니라 프로퍼티인 것은 요구가 "설정값 외부화" 까지이기 때문이다. 재배포
 * 없이 바꿔야 한다면 {@code estimate_validation_rule} 처럼 테이블로 올리는 것이 다음 단계다 —
 * 그쪽은 관리자가 화면에서 바꾸는 경로가 이미 있다.
 *
 * @param minRefCases   이 건수 <b>미만</b>이면 낮은 신뢰도. 참조 사례가 적으면 통계가 흔들린다
 * @param warnFromStage 이 완화 단계 <b>이상</b>(= 이만큼 이상 넓혔으면)이면 낮은 신뢰도
 */
@Validated
@ConfigurationProperties(prefix = "app.estimate-confidence")
public record LowConfidenceRule(

        @Min(value = 0, message = "min-ref-cases 는 음수일 수 없습니다.")
        int minRefCases,

        FallbackStage warnFromStage) {

    public LowConfidenceRule {
        if (warnFromStage == null) {
            throw new IllegalArgumentException(
                    "app.estimate-confidence.warn-from-stage 는 필수입니다 (MODEL · CAR_CLASS · ALL).");
        }
    }

    /**
     * 이 항목이 낮은 신뢰도인가.
     *
     * <p><b>완화 단계와 사례 수 둘 중 하나만 걸려도 참이다.</b> 근거가 약해지는 경로가 둘이고,
     * 둘 다 걸려야 경고한다면 "전국 사례를 다 뒤졌는데 세 건뿐" 같은 가장 약한 경우만 잡힌다.
     *
     * <p><b>{@code fallbackStage} 가 없으면 완화 조건은 보지 않는다.</b> 산정 로직이 값을
     * 채우지 않은 것이지 "완화하지 않았다" 가 아니다 — 모르는 것을 안전한 쪽으로 단정하지
     * 않는다. 사례 수 조건은 그대로 본다.
     *
     * @param refCaseCount  통계 산정에 쓴 전체 사례 수. {@code null} 이면 0 으로 본다
     * @param fallbackStage 어디까지 조건을 넓혔는지. {@code null} 이면 판단하지 않는다
     */
    public boolean isLowConfidence(Integer refCaseCount, FallbackStage fallbackStage) {
        return hasTooFewCases(refCaseCount) || isRelaxedEnough(fallbackStage);
    }

    private boolean hasTooFewCases(Integer refCaseCount) {
        return (refCaseCount == null ? 0 : refCaseCount) < minRefCases;
    }

    /**
     * <b>{@link FallbackStage} 의 선언 순서가 곧 "얼마나 넓혔나" 다</b> — MODEL(안 넓힘) →
     * CAR_CLASS(한 단계) → ALL(전부). 그래서 {@code ordinal} 비교가 성립한다. 값을 중간에
     * 끼워 넣으면 이 판정이 조용히 바뀌므로 그 enum 에 주석으로 적어 두었다.
     */
    private boolean isRelaxedEnough(FallbackStage fallbackStage) {
        return fallbackStage != null && fallbackStage.ordinal() >= warnFromStage.ordinal();
    }
}
