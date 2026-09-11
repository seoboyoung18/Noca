package com.ssafy.a307.admin;

import org.springframework.http.HttpStatus;

/**
 * 관리자 API 가 {@code error.code} 로 내보내는 안정적인 사유.
 *
 * <p><b>왜 {@code ErrorCode} 를 그대로 쓰지 않는가</b> — 공통 {@code ErrorCode} 는 HTTP 상태를
 * 뜻하는 8개뿐이라 "중복" 과 "버전 충돌" 과 "참조 중" 이 전부 {@code CONFLICT} 하나가 된다.
 * 그러면 FE 가 셋을 가르려고 <b>한글 메시지를 문자열 비교</b>해야 하는데, 메시지는 문구가
 * 바뀌는 값이라 그런 코드는 조용히 깨진다.
 *
 * <p>{@code ErrorCode} 열거형 밖의 값이라는 점은 의도적이다 — 인증의 {@code SIGNUP_REQUIRED}
 * 와 사고 이미지의 검증 사유가 이미 같은 방식이다. FE 는 {@code error.code} 를 {@code string}
 * 으로 받아야 한다.
 */
public enum AdminErrorCode {

    // ── 404 ───────────────────────────────────────────────────────────────
    /** 대상이 없다. 관리자 API 는 비활성 항목도 보므로 "꺼져 있다" 와는 다르다. */
    ADMIN_TARGET_NOT_FOUND(HttpStatus.NOT_FOUND),

    // ── 409 중복 ──────────────────────────────────────────────────────────
    /** {@code (manufacturer, model_name)} 이 이미 있다. 비활성 모델과도 겹칠 수 없다. */
    DUPLICATE_VEHICLE_MODEL(HttpStatus.CONFLICT),
    DUPLICATE_PART_CODE(HttpStatus.CONFLICT),
    DUPLICATE_PART_NAME_MAPPING(HttpStatus.CONFLICT),

    // ── 409 동시 수정 ─────────────────────────────────────────────────────
    /** 읽은 뒤 남이 먼저 바꿨다. 다시 읽고 다시 시도해야 한다. */
    VERSION_CONFLICT(HttpStatus.CONFLICT),

    // ── 409 참조 무결성 ───────────────────────────────────────────────────
    /** 다른 데이터가 쓰고 있어 지울 수 없다. */
    CODE_IN_USE(HttpStatus.CONFLICT),
    /** 활성 규칙이 참조 중이라 끌 수 없다. 규칙을 먼저 바꿔야 한다. */
    REFERENCED_BY_ACTIVE_RULE(HttpStatus.CONFLICT),
    /**
     * 정규화하면 이미 있는 다른 부품의 별칭과 같아진다.
     * <p>
     * 그대로 두면 런타임 사전이 <b>두 항목을 조용히 버린다</b>
     * ({@code PartNameMappingService.loadDictionary} 의 모호성 제거). 등록 시점에 막아
     * 관리자가 사라진 이유를 모른 채 남지 않게 한다.
     */
    NORMALIZED_NAME_CONFLICT(HttpStatus.CONFLICT),
    /** 같은 적용 범위에 심각도 구간이 겹치는 활성 규칙이 있다. */
    OVERLAPPING_RULE_RANGE(HttpStatus.CONFLICT),
    /** 마지막 활성 규칙이라 끌 수 없다 — 끄면 결정 경로가 통째로 사라진다. */
    LAST_ACTIVE_RULE(HttpStatus.CONFLICT),

    // ── 400 입력 ──────────────────────────────────────────────────────────
    /** 비활성 코드는 새 매핑·새 규칙의 대상이 될 수 없다. */
    INACTIVE_CODE(HttpStatus.BAD_REQUEST),
    /** canonical code 목록에 없는 값. 관리자가 새 canonical code 를 만들 수는 없다. */
    UNKNOWN_CODE(HttpStatus.BAD_REQUEST),
    /** 심각도 하한이 상한보다 크거나 같다. */
    INVALID_SEVERITY_RANGE(HttpStatus.BAD_REQUEST),
    /** 이상 탐지 임계값의 교차 필드 관계가 깨졌다. */
    INVALID_RULE_VALUE(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;

    AdminErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
