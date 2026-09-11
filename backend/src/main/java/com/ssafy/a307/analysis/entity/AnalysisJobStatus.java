package com.ssafy.a307.analysis.entity;

/**
 * 분석 작업 상태. {@code ck_aj_status CHECK (status IN ('QUEUED','PROCESSING','COMPLETED','FAILED'))}
 * 와 같은 네 값이며, 상수명이 DB 값과 같아 컨버터가 필요 없다.
 *
 * <p><b>상태 전이는 이 작업(S15P21A307-218)의 범위가 아니다.</b> 작업을 만들고 진행시키는 쪽은
 * 비동기 분석 요청 스토리({@code S15P21A307-155}, 서보영 담당)이고, 적재는 이미 있는 작업에
 * 결과를 채워 넣을 뿐이다. 그래서 여기에 {@code markCompleted()} 같은 전이 메서드를 두지 않았다 —
 * 두 곳에서 상태를 옮기면 어느 쪽이 옳은지 알 수 없게 된다.
 */
public enum AnalysisJobStatus {
    QUEUED,
    PROCESSING,
    COMPLETED,
    FAILED
}
