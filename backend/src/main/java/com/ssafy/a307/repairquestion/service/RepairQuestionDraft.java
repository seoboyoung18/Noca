package com.ssafy.a307.repairquestion.service;

/**
 * 저장 직전의 질문 한 줄. <b>근거는 이미 백엔드가 확정해 두었다.</b>
 *
 * <p>LLM 은 "이 질문이 어느 부품 이야기인가" 만 말한다. {@code damage_type} · {@code repair_method}
 * 와 부품 한글명은 {@code damaged_part} · {@code part_code} 에서 <b>복사</b>해 채운다 — 판정을
 * LLM 이 정하게 두면 {@code ck_rqi_damage} · {@code ck_rqi_method} 어휘 밖의 값이 들어와 INSERT 가
 * 거부되거나, 더 나쁘게는 분석이 말하지 않은 판정이 사용자에게 사실처럼 보인다.
 *
 * @param basis 부품에 매이지 않은 질문이면 {@code null}. 그때 근거 네 열이 모두 비고
 *              {@code ck_rqi_part} 를 만족한다
 */
public record RepairQuestionDraft(String content, RepairQuestionContext.DamagedPartView basis) {

    public boolean grounded() {
        return basis != null;
    }
}
