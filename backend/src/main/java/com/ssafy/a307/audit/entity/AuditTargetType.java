package com.ssafy.a307.audit.entity;

/**
 * 감사 대상의 종류. {@code audit_log.target_type} 에 이름 그대로 들어간다.
 *
 * <p>enum 으로 고정하는 이유는 조회 필터가 이 값으로 인덱스를 타기 때문이다
 * ({@code ix_audit_target}). 자유 문자열이면 오타 하나로 이력이 검색되지 않는다.
 */
public enum AuditTargetType {

    VEHICLE_MODEL,
    PART_CODE,
    PART_NAME_MAPPING,
    REPAIR_CODE,
    REPAIR_METHOD_RULE,
    ESTIMATE_VALIDATION_RULE
}
