package com.ssafy.a307.repairchecklist.entity;

/**
 * 체크리스트 생성 상태. 정본 DDL 의
 * {@code ck_rcl_status CHECK (status IN ('QUEUED','PROCESSING','COMPLETED','FAILED'))} 와 같은
 * 네 값이며, 상수명이 DB 값과 같아 컨버터가 필요 없다 — {@code AnalysisJobStatus} 와 같은 형태다.
 *
 * <p><b>네 값을 그대로 내보낸다.</b> {@code S15P21A307-461} 제목은 "대기·완료·실패" 세 가지를
 * 말하지만, 스키마도 집안의 다른 상태 열거형({@code AnalysisJobStatus} ·
 * {@code ValidationStatus})도 모두 네 값이다. {@code QUEUED} 와 {@code PROCESSING} 을 "대기" 로
 * 합치는 것은 <b>화면 결정</b>이고, 서버가 미리 접으면 FE 가 "생성 중" 을 구분해 보여 줄 수 없다.
 * 셋으로 줄이는 쪽은 되돌릴 수 없고, 넷으로 주는 쪽은 FE 가 언제든 접을 수 있다.
 */
public enum RepairChecklistStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED;

    /** 더 이상 워커가 건드리지 않는 상태인가. */
    public boolean finished() {
        return this == COMPLETED || this == FAILED;
    }
}
