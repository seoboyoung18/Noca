package com.ssafy.a307.batch.entity;

/**
 * 배치 실행 상태 (S15P21A307-355).
 *
 * <p><b>네 값은 {@code ck_bje_status} 가 정한 것이다.</b> 정본 DDL 의
 * {@code CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED'))} 와 글자까지 같아야
 * 한다 — 여기에 값을 더하면 DB 가 거부한다.
 *
 * <p>{@code PARTIAL} 이 있는 것이 다른 상태 열거형과 다른 점이다. 적재 배치는 수천 건을
 * 다루므로 <b>일부만 실패해도 나머지는 들어간다.</b> 그것을 {@code FAILED} 로 뭉치면 관리자가
 * "전부 실패" 로 읽는다.
 */
public enum BatchJobStatus {

    /** 돌고 있다. {@code completed_at} 이 비어 있다. */
    RUNNING,

    /** 전부 성공했다. */
    SUCCEEDED,

    /** 일부만 성공했다. 격리된 건은 {@code data_validation_error} 에 남는다. */
    PARTIAL,

    /** 실패했다. 사유는 {@code error_message} 에 있다. */
    FAILED
}
