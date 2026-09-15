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
    ESTIMATE_VALIDATION_RULE,
    /**
     * 사고 데이터 검수 (S15P21A307-352). 앞의 여섯과 달리 <b>마스터가 아니라 사용자 데이터</b>에
     * 대한 판정이다 — 이 테이블이 마스터 변경 기록 전용이 아니게 되는 첫 값이다.
     * 상태의 정본은 {@code accident_review} 이고 여기는 "누가 언제 눌렀나" 의 이력이다
     * (answer71 §2-2).
     */
    ACCIDENT_REVIEW
}
