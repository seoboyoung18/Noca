package com.ssafy.a307.review.entity;

/**
 * 사고 데이터 검수 상태. 정본 DDL 의
 * {@code ck_ar_status CHECK (status IN ('PENDING','APPROVED','REJECTED'))} 와 같은 세 값이며,
 * 상수명이 DB 값과 같아 컨버터가 필요 없다 — {@code AnalysisJobStatus} 와 같은 형태다.
 *
 * <p><b>다른 상태 열거형과 달리 셋이다.</b> {@code analysis_job} · {@code repair_checklist} 는
 * {@code QUEUED · PROCESSING · COMPLETED · FAILED} 네 값인데 여기는 "처리 중" 이 없다 —
 * 사람이 보고 판정하는 일이라 서버가 붙들고 있는 구간이 없기 때문이다.
 * 정본 DDL 12-3 절 주석에 같은 말이 적혀 있다.
 */
public enum AccidentReviewStatus {

    /** 큐에 올라와 있고 아직 아무도 판정하지 않았다. */
    PENDING,

    /** 재학습 데이터로 쓸 수 있다. {@code snapshot_actual_repair_cost} 가 이때 채워진다. */
    APPROVED,

    /** 쓰지 않는다. {@code reject_reason} 이 반드시 있다({@code ck_ar_reject}). */
    REJECTED;

    /** 사람이 이미 판정했는가. */
    public boolean decided() {
        return this != PENDING;
    }
}
