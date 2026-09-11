package com.ssafy.a307.admin.dto;

import com.ssafy.a307.estimatevalidation.entity.RepairMethodRule;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * @param partCode     {@code null} 이면 그 손상 유형 전체에 적용되는 기본 규칙
 * @param maxInclusive {@code false} 면 구간이 {@code [min, max)} 다. 이웃 구간과 경계값이
 *                     겹치지 않게 하려고 기본이 열린 상한이다
 * @param priority     함께 걸리면 큰 쪽이 이긴다. 같으면 부품 지정 규칙이 기본 규칙을 이긴다
 */
public record RepairMethodRuleResponse(
        Long ruleId,
        String damageType,
        String partCode,
        BigDecimal severityMin,
        BigDecimal severityMax,
        boolean maxInclusive,
        String repairMethod,
        short priority,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public static RepairMethodRuleResponse from(RepairMethodRule rule) {
        return new RepairMethodRuleResponse(
                rule.getRuleId(), rule.getDamageType(), rule.getPartCode(),
                rule.getSeverityMin(), rule.getSeverityMax(), rule.isMaxInclusive(),
                rule.getRepairMethod(), rule.getPriority(), rule.isActive(),
                rule.getVersion(), rule.getCreatedAt(), rule.getUpdatedAt());
    }
}
