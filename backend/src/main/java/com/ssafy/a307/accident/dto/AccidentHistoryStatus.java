package com.ssafy.a307.accident.dto;

/**
 * 사고 이력 목록의 상태 배지(Task 225).
 *
 * <p><b>저장하는 컬럼이 아니라 유도값이다.</b> 정본 {@code accident} 에 상태 열이 없고,
 * 요구사항이 "상태" 라고만 적어 어떤 축인지 정하지 않았다. 스키마를 늘리는 대신 이미 있는
 * 사실 세 가지로 유도한다 — 이미지 장수, 견적 유무, 실제 수리비 기록 여부.
 *
 * <p>순서가 곧 진행 단계다. 뒤쪽이 앞쪽을 덮는다 — 견적이 있으면 이미지가 몇 장이든
 * {@link #ESTIMATED} 이고, 실제 수리비가 기록됐으면 {@link #REPAIR_RECORDED} 다.
 *
 * <p><b>확정본이 아니다.</b> 기획이 상태 축을 정하면 그때 값을 맞춘다. 그때 이 enum 만 바꾸면
 * 되도록 유도 규칙을 {@code AccidentService} 한 곳에 모아 두었다. 화면 문구는 FE 가 정한다 —
 * 서버가 한글 라벨을 내려보내면 문구를 바꿀 때마다 배포가 필요하다.
 */
public enum AccidentHistoryStatus {

    /** 접수만 됐다. 이미지가 한 장도 없다. */
    RECEIVED,

    /** 이미지가 올라갔다. 아직 견적이 없다. */
    IMAGES_UPLOADED,

    /** 견적이 산출됐다. */
    ESTIMATED,

    /** 실제 수리비가 기록됐다. 사용자 입장에서 끝난 건이다. */
    REPAIR_RECORDED
}
