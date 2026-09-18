package com.ssafy.a307.estimate.narrative;

import java.util.List;

/**
 * LLM 이 만든 <b>문장</b>만 담는다 (S15P21A307-537). {@code estimate_narrative.content} 에 이
 * 모양 그대로 저장된다.
 *
 * <p><b>금액·등급·판정이 이 타입에 없는 것이 설계다.</b> 그 값들은 이미 {@code estimate} ·
 * {@code estimate_item} 이 가지고 있고, LLM 은 그것을 사람이 읽을 문장으로 옮기는 일만 한다.
 * 필드를 늘리고 싶어지면 먼저 "이 값을 LLM 이 만들어도 되는가" 를 묻는다 — 지어낸 금액은
 * 그대로 협상 근거가 된다({@code ReportNarrative} 가 같은 경계를 지킨다).
 *
 * @param summary    견적 전체를 어떻게 읽을지 한 문단. 없으면 {@code null}
 * @param cautions   지금 확인해 두면 좋은 것 두세 줄. 없으면 빈 목록
 * @param basisNotes 항목별 근거 문장을 다듬은 것. <b>규칙이 만든 문장을 대체한다</b> —
 *                   없는 부위는 담기지 않고, 그 자리는 규칙 문장이 그대로 나간다
 */
public record EstimateNarrativeContent(String summary, List<String> cautions,
                                       List<BasisNote> basisNotes) {

    public EstimateNarrativeContent {
        cautions = cautions == null ? List.of() : List.copyOf(cautions);
        basisNotes = basisNotes == null ? List.of() : List.copyOf(basisNotes);
    }

    /** @param partCode 어느 부위의 근거 문장인가. 프롬프트에 넘긴 목록 안의 값만 온다 */
    public record BasisNote(String partCode, String text) {
    }

    public static EstimateNarrativeContent empty() {
        return new EstimateNarrativeContent(null, List.of(), List.of());
    }

    /** 그 부위의 다듬어진 근거 문장. 없으면 {@code null} 이고 호출자가 규칙 문장을 쓴다. */
    public String noteFor(String partCode) {
        if (partCode == null) {
            return null;
        }
        return basisNotes.stream()
                .filter(note -> partCode.equals(note.partCode()))
                .map(BasisNote::text)
                .findFirst()
                .orElse(null);
    }

    public boolean isEmpty() {
        return (summary == null || summary.isBlank()) && cautions.isEmpty() && basisNotes.isEmpty();
    }
}
