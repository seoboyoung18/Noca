package com.ssafy.a307.estimate.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 견적 항목 하나의 산정 근거. {@code estimate_item.ref_condition} JSONB 의 내용 계약이다.
 *
 * <p><b>왜 스냅샷인가</b> — 사례 데이터는 계속 적재되고, AI 가 같은 조건으로 다시 검색해도
 * 그때는 다른 사례를 본다. 근거를 조회할 때마다 다시 계산하면 어제 보여 준 견적의 근거가
 * 오늘 바뀐다. 견적에 버전을 쌓은 것과 같은 이유다
 * ({@link com.ssafy.a307.estimate.service.EstimateVersioningService}).
 *
 * <p>AI 연동 계약도 같은 말을 한다 — "백엔드가 사례를 다시 검색하지 않고 AI 가 준 ID 로
 * 조회만 합니다. 재검색하면 AI 가 본 사례와 달라져 근거가 어긋나기 때문입니다".
 *
 * <p><b>파생할 수 있는 값은 담지 않는다.</b> 다음은 이미 다른 곳에 불변으로 남아 있어 여기에
 * 다시 넣으면 두 곳이 어긋날 수 있다.
 * <ul>
 *   <li>차량 조건({@code modelId}·{@code carClass}) — {@code accident} 의 스냅샷 컬럼.
 *       접수 당시 값을 보존하도록 설계돼 있어 나중에 차량을 고쳐도 변하지 않는다</li>
 *   <li>부위·파손 유형 — {@code damaged_part}</li>
 *   <li>참조 사례 건수 — {@code estimate_item.ref_case_count} 컬럼</li>
 *   <li>수리 방식 — {@code estimate_item.repair_method} 컬럼</li>
 * </ul>
 *
 * <p><b>참조한 사례 ID 는 여기 담는다.</b> 화면의 "이 사례들 보기"가 <b>항목별 근거 안에</b>
 * 있어 앞범퍼 사례와 헤드램프 사례를 구분해야 하기 때문이다. {@code refCaseCount} 와는 다른
 * 값이다 — 그쪽은 통계 산정에 쓴 전체 건수고, 이쪽은 화면에 보여 줄 대표 사례다(항목당 10건).
 * 남은 것이 <b>다른 어디에도 남지 않는 값</b>들이다. 조건을 어디까지 넓혔는지, 그때 통계가
 * 얼마였는지, 참조한 사례가 몇 년 것이었는지, 수리 방식을 왜 그렇게 골랐는지.
 *
 * <p><b>모든 필드가 {@code null} 일 수 있다.</b> AI 가 항목마다 근거를 보내지만 전부 선택
 * 필드이고, 산정하지 못한 견적에는 항목 자체가 없다. 요구사항이 "근거가 부족한 항목은
 * 그 사실이 명시된다"(명세서 51행)고 했으므로, 비어 있음은 오류가 아니라
 * <b>표시해야 할 상태</b>다.
 *
 * <p><b>값을 채우는 곳은 {@code AnalysisResultPersister} 다</b>(S15P21A307-157). AI callback 의
 * {@code items[]} 근거 필드를 그대로 옮긴다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefCondition(
        FallbackStage fallbackStage,
        CostDistribution costDistribution,
        Integer refYearFrom,
        Integer refYearTo,
        RepairMethodReason repairMethodReason,
        List<Long> referencedCaseIds) {

    /** 근거가 하나도 없는 상태. {@code '{}'} 로 저장된 행과 읽지 못한 행이 여기로 온다. */
    public static final RefCondition EMPTY =
            new RefCondition(null, null, null, null, null, null);

    /**
     * 참조 사례 ID. <b>{@code null} 대신 빈 목록을 돌려준다</b> — 소비처가 매번 null 을 막지
     * 않게 한다. 사례 조회(S15P21A307-236)가 이 값으로 {@code repair_case} 를 찾는다.
     */
    public List<Long> referencedCaseIds() {
        return referencedCaseIds == null ? List.of() : referencedCaseIds;
    }

    /** 화면에 "근거 없음"을 띄울지 가른다. 하나라도 있으면 보여 줄 것이 있다. */
    public boolean isEmpty() {
        return fallbackStage == null
                && costDistribution == null
                && refYearFrom == null
                && refYearTo == null
                && repairMethodReason == null
                && referencedCaseIds().isEmpty();
    }

    /**
     * 산정에 쓴 유사 사례 비용 분포.
     *
     * <p>25~75분위를 범위로 쓰는 것은 명세서 48행이 정한 1차 산정 방식이다. 부품 단가와
     * 표준 작업시간으로 계산하는 2차 교차 검증은 MVP 범위가 아니다.
     *
     * <p><b>AI 가 계산해 보낸 값을 그대로 옮긴다.</b> 백엔드가 집계하지 않는다 —
     * 2026-09-12 AI 연동 계약이 유사 사례 검색과 견적 산정을 AI 담당으로 정했다.
     *
     * <p>계약의 정의는 "사례 견적서 행 중 <b>실제 정산에 들어간 금액만</b> 집계합니다" 다.
     * 참고가 행이 빠지므로 화면에 "실제 청구 기준" 이라고 쓸 수 있다.
     *
     * <p>처음 설계(2026-09-10)는 {@code repair_cost_stat} 에서 옮길 계획이었으나 그 경로는
     * 쓰지 않는다. 그 테이블의 소비처는 지금 견적서 검증뿐이다.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CostDistribution(Integer p25, Integer median, Integer p75) {
    }

    /**
     * 수리 방식을 그렇게 고른 이유.
     *
     * <p><b>후보 목록을 함께 저장한다.</b> 파손 유형별 수리 방식 후보는 파이프라인의
     * {@code DEFAULT_WORK_BY_DAMAGE} 가 갖고 있는데, 그 표는 Python 쪽에만 있고 바뀔 수 있다.
     * 결과만 남기면 나중에 "후보가 둘이었는데 이걸 골랐다"인지 "애초에 하나뿐이었다"인지
     * 구분할 수 없다 — 사용자에게 설명할 수 있는지가 갈린다.
     *
     * <p><b>{@code reasonCode} 의 어휘는 여기서 정하지 않는다.</b> 무엇이 후보 둘 사이의 선택을
     * 가르는지는 <b>AI 가 정한다</b> — 2026-09-12 계약의 "정해야 할 것" 4번이며 소유가 AI 다.
     * 아직 확정되지 않았고, 백엔드가 값을 지어내면 AI 가 다른 결론을 낼 때 저장된 근거가
     * 거짓이 된다. 받은 값을 그대로 보존하기만 한다.
     *
     * <p>계약은 확정값을 보내더라도 {@code candidates} 를 함께 남기라고 요구한다 —
     * S15P21A307-196 규칙이 선 뒤 재판정할 때 근거가 되기 때문이다.
     *
     * @param candidates 그 파손 유형에서 가능했던 수리 방식들. AI 가 보낸다
     * @param reasonCode 선택 사유 코드. 어휘는 AI 가 확정한다(계약 "정해야 할 것" 4번)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RepairMethodReason(List<String> candidates, String reasonCode) {
    }
}
