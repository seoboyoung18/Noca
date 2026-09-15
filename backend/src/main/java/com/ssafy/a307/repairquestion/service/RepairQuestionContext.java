package com.ssafy.a307.repairquestion.service;

import java.util.List;

/**
 * LLM 지시문에 실을 사고 정보. <b>이미 확정된 값만 담는다.</b>
 *
 * <p>차량 값은 {@code accident} 의 <b>스냅샷 열</b>에서 온다 — 현재 {@code vehicle} 값이 아니다.
 * 사고 당시의 차를 기준으로 물어볼 것을 만들어야 하고, 스냅샷을 쓰는 것이 이 저장소의 규칙이다
 * ({@code RepairChecklistContext} 와 같은 판단).
 *
 * @param parts    분석이 찾아낸 손상 부위. <b>없을 수 있다</b> — 분석을 아직 돌리지 않았거나
 *                 실패한 사고다. 그때는 차량 정보만으로 만든다
 * @param estimate 예상 견적 요약. <b>{@code null} 일 수 있다</b> — 견적이 아직 없는 사고다
 */
public record RepairQuestionContext(
        String manufacturer,
        String modelName,
        Short modelYear,
        List<DamagedPartView> parts,
        EstimateView estimate) {

    /**
     * 손상 부위 한 줄. 판정 두 값은 DDL 표기 그대로다 — 백엔드가 한글로 옮기지 않는다.
     *
     * <p><b>{@code partName} 만 예외로 한글이다.</b> {@code part_code.name_ko} 를 그대로 실어
     * 보낸다. 질문은 차주가 정비소에서 <b>그대로 읽는 문장</b>이라 "REAR_DOOR 는 …" 이 아니라
     * "리어 도어는 …" 이 되어야 하고({@code S15P21A307-476} 본문 예시), 그 이름의 정본은
     * 부품 마스터다. 같은 값이 {@code snapshot_part_name} 으로도 저장된다.
     */
    public record DamagedPartView(String partCode, String partName,
                                  String damageType, String repairMethod) {
    }

    /**
     * 예상 견적 요약.
     *
     * <p><b>금액을 담지 않는다.</b> 지시문에 총액이 들어가면 LLM 이 그 숫자를 질문 문장에 그대로
     * 옮겨 적을 수 있고, 그러면 우리 추정치가 정비소 앞에서 확정된 사실처럼 읽힌다. 이 도구는
     * 금액을 판정하지 않는다({@code RepairChecklistGenerator} 지시문도 같은 이유로 금액을 금지한다).
     *
     * <p>대신 <b>어디를 더 확인해야 하는지</b>를 담는다 — 견적 자체가 불가능했는지, 신뢰도 등급이
     * 무엇인지다. {@code LOW} 면 물어볼 거리가 더 많다는 뜻이고 그것이 이 기능의 요점이다.
     *
     * @param confidenceGrade {@code HIGH} · {@code MEDIUM} · {@code LOW}. 없으면 {@code null}
     */
    public record EstimateView(boolean estimable, String confidenceGrade) {
    }
}
