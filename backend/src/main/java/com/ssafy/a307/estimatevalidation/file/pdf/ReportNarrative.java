package com.ssafy.a307.estimatevalidation.file.pdf;

import java.util.Map;

/**
 * LLM 이 만든 <b>문장</b>만 담는다.
 *
 * <p><b>숫자·등급·판정이 이 타입에 없는 것이 설계다.</b> 그것들은 이미 규칙 엔진과 검증
 * 엔진이 정했고, LLM 은 그 값을 사람이 읽을 문장으로 옮기는 일만 한다. 필드를 늘리고 싶어지면
 * 먼저 "이 값을 LLM 이 만들어도 되는가" 를 묻는다 — 지어낸 금액은 그대로 협상 근거가 된다.
 *
 * <p>모든 필드가 <b>없어도 된다.</b> LLM 호출이 실패하면 {@link #empty()} 가 쓰이고,
 * PDF 는 규칙 기반 요약과 사유만으로 그대로 생성된다.
 *
 * @param gradeExplanation 등급을 풀어 쓴 한두 문장. 없으면 null
 * @param itemNotes        {@code lineNo} → 항목 보충 문장. 없는 행은 담기지 않는다
 */
public record ReportNarrative(String gradeExplanation, Map<Integer, String> itemNotes) {

    public ReportNarrative {
        itemNotes = itemNotes == null ? Map.of() : Map.copyOf(itemNotes);
    }

    public static ReportNarrative empty() {
        return new ReportNarrative(null, Map.of());
    }

    /** 해당 행의 보충 문장. 없으면 null 이고 템플릿이 그 자리를 비운다. */
    public String noteFor(int lineNo) {
        return itemNotes.get(lineNo);
    }

    public boolean isEmpty() {
        return (gradeExplanation == null || gradeExplanation.isBlank()) && itemNotes.isEmpty();
    }
}
