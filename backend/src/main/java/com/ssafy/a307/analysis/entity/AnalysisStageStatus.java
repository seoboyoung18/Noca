package com.ssafy.a307.analysis.entity;

/**
 * 단계 하나의 상태. {@code ck_as_status CHECK (status IN ('PENDING','RUNNING','DONE','FAILED'))} 와
 * 같은 네 값이며, 상수명이 DB 값과 같아 컨버터가 필요 없다.
 *
 * <p>{@link AnalysisJobStatus} 와 값이 다르다. 작업 전체는 {@code QUEUED} 로 시작하고 단계는
 * {@code PENDING} 으로 시작한다 — 같은 뜻처럼 보이지만 DDL 의 CHECK 가 서로 다른 집합을 받으므로
 * <b>한쪽 값을 다른 쪽에 넣으면 INSERT 가 거절된다.</b> 두 enum 을 합치지 않는 이유다.
 */
public enum AnalysisStageStatus {

    /** 아직 시작하지 않았다. 정본 DDL 의 DEFAULT 다. */
    PENDING,

    /** 진행 중. 화면의 "현재 단계" 가 이것이다. */
    RUNNING,

    /** 끝났다. 화면의 "N/4" 에서 N 으로 세는 것이 이 상태다. */
    DONE,

    /** 이 단계에서 실패했다. 사유는 {@code detail} 에 담긴다. */
    FAILED
}
