package com.ssafy.a307.repairquestion.entity;

/**
 * 질문이 어디서 왔는가. {@code ck_rqi_source CHECK (source IN ('AI','USER'))}.
 *
 * <p><b>{@code COMMON} 이 없다.</b> 체크리스트({@code RepairChecklistItemSource})는 세 값인데
 * 여기는 둘이다 — 질문에는 {@code repair_checklist_common_item} 같은 공통 문안 마스터가 없기
 * 때문이다({@code S15P21A307-510} 이 그렇게 정했고 정본 DDL 12-2 절 주석에 이유가 있다).
 *
 * <p><b>{@code USER} 를 만드는 코드는 이 작업에 없다.</b> {@code S15P21A307-476} · {@code -477} 은
 * 생성 · 개별 복사 · 전체 복사만 요구하고 사용자가 질문을 직접 추가하는 기능은 어느 티켓에도
 * 없다. 스키마가 값을 남겨 둔 이유는 재생성 때 "지워도 되는 행" 과 "지우면 안 되는 행" 을 가를
 * 축이 필요해서다 — 그 기능이 생기면 여기에 붙는다.
 */
public enum RepairQuestionItemSource {
    AI,
    USER
}
