package com.ssafy.a307.admin.dto;

import com.ssafy.a307.estimatevalidation.entity.EstimateValidationRule;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 이상 탐지 임계값 한 벌.
 *
 * <p><b>{@code ruleVersion} 이 곧 식별자이자 이력이다.</b> 현재 규칙은 가장 큰 버전이고,
 * 과거 검증은 {@code estimate_validation.rule_version} 으로 자기가 쓴 버전을 가리킨다.
 *
 * @param referencePercentile <b>현재 판정에 쓰이지 않는다.</b> 검증 엔진이 {@code repair_cost_stat}
 *                            의 P75 컬럼을 직접 읽고 있어 이 값이 소비되는 지점이 없다.
 *                            값은 보존하되 화면에서 "적용 중" 이라고 안내하면 안 된다
 */
public record EstimateValidationRuleResponse(
        int ruleVersion,
        int referencePercentile,
        BigDecimal severeOverP75Multiplier,
        BigDecimal cautionTotalDifferenceRatio,
        BigDecimal needsReviewTotalDifferenceRatio,
        int needsReviewItemCount,
        Long changedBy,
        String changeNote,
        Instant createdAt) {

    public static EstimateValidationRuleResponse from(EstimateValidationRule rule) {
        return new EstimateValidationRuleResponse(
                rule.getRuleVersion(), rule.referencePercentile(),
                rule.severeOverP75Multiplier(), rule.cautionTotalDifferenceRatio(),
                rule.needsReviewTotalDifferenceRatio(), rule.needsReviewItemCount(),
                rule.getChangedBy(), rule.getChangeNote(), rule.getCreatedAt());
    }
}
