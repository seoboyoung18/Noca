package com.ssafy.a307.accident.dto;

/**
 * 사고 이력 목록의 상태 배지(Task 225 · prompt58).
 *
 * <p><b>저장하는 컬럼이 아니라 유도값이다.</b> 정본 {@code accident} 에 상태 열이 없다.
 * 스키마를 늘리는 대신 이미 있는 사실로 유도한다 — 이미지 장수, 견적 유무,
 * <b>가장 최근 분석 작업의 상태</b>.
 *
 * <h2>⚠️ 선언 순서는 더 이상 우선순위가 아니다</h2>
 *
 * <p>예전 javadoc 은 "순서가 곧 진행 단계이고 뒤쪽이 앞쪽을 덮는다" 고 적었다. 분석 상태 두 값이
 * 들어오면서 그 규칙이 깨졌다 — {@link #ANALYSIS_FAILED} 는 {@link #ESTIMATED} 를 이기지만
 * <b>진행이 더 나아간 상태가 아니다.</b> 선언 순서는 사람이 읽기 좋은 진행 순서일 뿐이고,
 * 실제 우선순위는 {@code AccidentService#statusOf} 한 곳에만 있다.
 *
 * <p>우선순위는 이렇다. 위가 이긴다.
 *
 * <ol>
 *   <li>{@link #ANALYSIS_FAILED} — 최신 작업이 {@code FAILED}</li>
 *   <li>{@link #ANALYZING} — 최신 작업이 {@code QUEUED} 또는 {@code PROCESSING}</li>
 *   <li>{@link #ESTIMATED} — 산정된 견적이 있다</li>
 *   <li>{@link #IMAGES_UPLOADED} — 이미지가 한 장이라도 있다</li>
 *   <li>{@link #RECEIVED} — 그 외</li>
 * </ol>
 *
 * <p><b>실패를 견적보다 위에 둔 이유</b> — 화면 21 이 {@code 분석 실패} 배지를 두는 목적이
 * 실패를 사용자에게 알리는 것이다. 예전 견적이 남아 있다고 {@code 견적 완료} 로 덮으면
 * 방금 건 재분석이 실패한 사실이 화면에서 사라진다. 사용자는 결과가 갱신된 줄 안다.
 *
 * <p><b>진행 중을 견적보다 위에 둔 이유</b> — 같다. 재분석 중이라는 사실이 예전 견적에 가려지면
 * 사용자가 지금 보는 금액이 최신인지 알 수 없다.
 *
 * <p><b>이미지가 0장인데 작업이 있으면</b> 분석 상태가 이긴다. 작업이 생겼다는 것은 분석이
 * 요청됐다는 뜻이고, 그때 장수는 더 이상 화면이 물어보는 질문이 아니다.
 *
 * <p><b>확정본이 아니다.</b> 기획이 상태 축을 정하면 그때 값을 맞춘다. 그때 이 enum 과
 * {@code AccidentService#statusOf} 만 바꾸면 되도록 유도 규칙을 그 한 곳에 모아 두었다.
 * 화면 문구는 FE 가 정한다 — 서버가 한글 라벨을 내려보내면 문구를 바꿀 때마다 배포가 필요하다.
 */
public enum AccidentHistoryStatus {

    /** 접수만 됐다. 이미지가 한 장도 없다. */
    RECEIVED,

    /** 이미지가 올라갔다. 아직 견적이 없다. */
    IMAGES_UPLOADED,

    /**
     * 분석이 돌고 있다. 최신 작업이 {@code QUEUED} 또는 {@code PROCESSING} 이다.
     * <p>
     * 둘을 나누지 않는다 — 사용자에게는 "아직 안 끝났다" 로 같고, 큐 대기와 처리 중을 가르면
     * 화면이 배지를 두 개 더 그려야 한다. 단계별 진척은
     * {@code GET /api/accidents/{accidentId}/analysis} 가 답한다.
     */
    ANALYZING,

    /**
     * 최신 분석 작업이 {@code FAILED} 다. 사유는 {@code analysis_job.failure_reason} 에 있고
     * 진행 상태 API 가 내보낸다.
     */
    ANALYSIS_FAILED,

    /** 견적이 산출됐다. */
    ESTIMATED,

    /**
     * 실제 수리비가 기록됐다. 사용자 입장에서 끝난 건이다.
     * <p>
     * <b>아직 유도하지 않는다.</b> {@code actual_repair_cost} 가 목록 투영에 없다 —
     * 금액이라 목록 노출 여부가 기획 결정이다. 값 자체는 예전부터 계약에 있었으므로 지운다고
     * FE 가 덜 쓰게 되지도 않아 그대로 둔다.
     */
    REPAIR_RECORDED
}
