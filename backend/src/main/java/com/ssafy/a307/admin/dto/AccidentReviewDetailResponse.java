package com.ssafy.a307.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 검수 대상 한 건의 비교 상세 (S15P21A307-514).
 *
 * <h2>목록 응답을 통째로 품는다</h2>
 *
 * <p>{@link #review} 가 목록의 {@link AccidentReviewResponse} <b>그 레코드</b>다. 필드를 따로
 * 나열하지 않았다 — 같은 값을 두 벌로 정의하면 한쪽만 고쳐져 어긋난다. 화면이 목록에서 상세로
 * 넘어갈 때 다시 그릴 필요가 없고, 판정 버튼을 그리려면 {@code reviewId}·{@code status} 가
 * 어차피 필요하다.
 *
 * <h2>상세가 더하는 것은 AI 산출물이다</h2>
 *
 * <p>금액·차량·판정 상태는 목록이 이미 준다. 여기가 더하는 것은 셋이다.
 *
 * <ol>
 *   <li>{@link #estimate} — AI 예상 견적. <b>{@code accident.actual_repair_cost} 와의 대조가
 *       검수의 실질이다</b>(answer71 §2-1, answer72 §2-1)</li>
 *   <li>{@link #damagedParts} — AI 부위 판정. 사용자가 고칠 수 없으므로 <b>대조가 아니라
 *       근거 제시</b>다. 관리자가 "이 사고에 이 금액이 말이 되나" 를 판단할 재료다</li>
 *   <li>{@link #detectionSummary}·{@link #analysisImages} — <b>AI 가 실제로 본 것 전부</b>
 *       (S15P21A307-514 후속 점검). {@link #damagedParts} 는 그 부분집합일 뿐이다 — 아래 참고</li>
 *   <li>{@link #checklistItems} — 사용자가 실제로 손댄 유일한 곳. {@code USER} 항목과
 *       체크·메모가 여기 있다</li>
 * </ol>
 *
 * <h2>없는 것이 정상이다</h2>
 *
 * <p>큐 적재 기준이 실제 수리비 입력이라 <b>분석을 한 번도 안 한 사고도 검수 대상이 된다</b>
 * (answer72 §2-1). 분석이 없으면 {@link #analysisJobId} 와 {@link #estimate} 가 {@code null} 이고
 * 목록들은 비어 있다. <b>500 을 내지 않는다.</b>
 *
 * <h2>🔴 {@code damagedParts} 는 AI 가 찾은 것의 부분집합이다</h2>
 *
 * <p>{@code damaged_part} 는 콜백의 {@code items[]} 로 만들어지는데, <b>공유 계약이 일부 검출을
 * 그 배열에서 빼도록 정해 두었다.</b> {@code CallbackItem} javadoc 이 계약을 인용한다 —
 * "{@code VECTOR_ONLY} 검출은 확정 부품이 아니므로 견적 {@code items[]} 에는 포함하지 않습니다."
 * 부위를 못 찾은 검출({@code part} 가 {@code null})도 {@code part_code NOT NULL} 이라 들어갈 수 없다.
 *
 * <p>즉 {@code damagedParts} 만 보면 <b>AI 가 8건을 검출했는데 2건만 보이는</b> 일이 생긴다.
 * "이 사고가 학습 데이터로 쓸 만한가" 를 판정하는 화면에서 그것은 치명적이다 — 나머지 6건이
 * 품질 미달({@code EXCLUDED})이었는지 부위가 모호했는지({@code VECTOR_ONLY}) 알 수 없다.
 *
 * <p>그래서 {@code analysis_image_result.detections} 원문에서 <b>판정에 필요한 값만 평탄화해</b>
 * {@link #analysisImages} 로 싣고, 한눈에 볼 수 있게 {@link #detectionSummary} 로 센다.
 * {@code geometry}(폴리곤)는 싣지 않는다 — 여기는 그림을 그리는 화면이 아니라 판정하는 화면이다.
 *
 * <p><b>일반 사용자는 이미 이 값을 본다.</b> {@code AnalysisResultResponse} 가 {@code detections}
 * 원문을 통째로 내보낸다. 검수자가 차주보다 적게 보고 있었다.
 *
 * @param analysisJobId   <b>이 응답이 보여 주는 분석 실행분.</b> 판정된 건은
 *                        {@code review.reviewedJobId} 와 같고, {@code PENDING} 인 건은 가장 최근
 *                        {@code COMPLETED} 분이다. 두 값이 다르면 기록된 것과 지금 보이는 것이
 *                        다르다는 뜻이다 — {@code review.reviewedJobId} 와 비교하면 알 수 있다
 * @param checklistStatus 체크리스트 생성 상태. 없으면 {@code null}
 */
public record AccidentReviewDetailResponse(
        AccidentReviewResponse review,
        Long analysisJobId,
        Estimate estimate,
        List<DamagedPart> damagedParts,
        DetectionSummary detectionSummary,
        List<AnalysisImage> analysisImages,
        String checklistStatus,
        List<ChecklistItem> checklistItems) {

    /**
     * AI 예상 견적 요약.
     *
     * <p><b>총액 셋을 그대로 준다.</b> {@code total_min}·{@code total_median}·{@code total_max}
     * 중 어느 것을 실제 수리비와 견줄지는 <b>기획이 정하지 않았다</b>. 서버가 하나를 고르면 그
     * 선택이 곧 검수 기준이 되는데, 그럴 근거가 아직 없다.
     *
     * <p>대신 판단이 필요 없는 신호 하나는 계산해 준다 — {@link #actualWithinRange}.
     * 실제 금액이 추정 구간 안에 들었는지는 어느 대푯값을 고르든 같은 답이다.
     *
     * @param estimable           {@code false} 면 총액 셋이 전부 {@code null} 이다.
     *                            그때는 {@link #nonEstimableReason} 이 그 사유다
     * @param actualWithinRange   실제 수리비가 {@code [totalMin, totalMax]} 안에 드는가.
     *                            <b>비교할 수 없으면 {@code null}</b> — 실제 금액이 없거나,
     *                            산정 불가 견적이거나, 구간이 비어 있는 경우다
     * @param version             {@code uk_est (job_id, version)} 의 그 버전. <b>가장 큰 값</b>이다
     */
    public record Estimate(
            Long estimateId,
            short version,
            boolean estimable,
            String nonEstimableReason,
            Integer totalMin,
            Integer totalMedian,
            Integer totalMax,
            String confidenceGrade,
            Boolean actualWithinRange,
            Instant createdAt) {
    }

    /**
     * AI 가 판정한 부위 한 줄.
     *
     * <p>한글 표시명은 {@code part_code} 마스터에서 붙인다 — 관리자 화면이 코드 32종을
     * 하드코딩하지 않게 한다({@code AnalysisResultQueryRepository.findParts} 와 같은 판단).
     *
     * @param repairMethod {@code null} 일 수 있다 — 후보가 둘인 손상은 규칙이 서기 전까지
     *                     확정하지 않는다({@code damaged_part.repair_method} 가 nullable 인 이유)
     */
    public record DamagedPart(
            String partCode,
            String partNameKo,
            String damageType,
            String repairMethod,
            BigDecimal confidence) {
    }

    /**
     * 검출 분포 한눈에 보기 (S15P21A307-514 후속 점검).
     *
     * <p>{@code searchability} 는 AI 가 검출마다 매긴 <b>쓸 수 있는 데이터인가</b> 판정이다
     * ({@code shared/vision/search_metadata.py._searchability}).
     *
     * <ul>
     *   <li>{@code STRICT} — 부품이 확정됐고 모호하지 않다</li>
     *   <li>{@code VECTOR_ONLY} — 부품 미확정이거나 {@code AMBIGUOUS}. <b>{@code damagedParts}
     *       에 없다</b></li>
     *   <li>{@code EXCLUDED} — 품질 미달이거나 손상 유형이 없다. 역시 보이지 않는다</li>
     * </ul>
     *
     * <p>⚠️ <b>이 값이 재학습 적재를 막지는 않는다.</b> 확인했다 —
     * {@code pipeline/jobs/load_service_accidents.py} 는 {@code accident} 만 읽고
     * {@code damaged_part}·{@code detections} 를 한 번도 참조하지 않는다. 그래서 이 숫자는
     * <b>강제가 아니라 판단 재료</b>다. "AI 도 쓸 수 없다고 본 검출이 대부분인 사고" 를 사람이
     * 알아보고 반려할 수 있게 하는 것이 목적이다.
     *
     * @param total       그 분석분의 전체 검출 수
     * @param withoutPart {@code part} 가 {@code null} 인 검출. 계약이 허용하는 정상 값이고
     *                    {@code part_code NOT NULL} 이라 {@code damaged_part} 에 들어갈 수 없다
     */
    public record DetectionSummary(
            int total,
            int strict,
            int vectorOnly,
            int excluded,
            int withoutPart) {

        public static final DetectionSummary EMPTY = new DetectionSummary(0, 0, 0, 0, 0);
    }

    /**
     * 분석에 쓰인 사진 한 장과 그 검출들.
     *
     * @param excluded        분석에서 제외된 사진이다. <b>제외가 많은 사고는 학습 데이터로서
     *                        약하다</b> — 관리자가 알아야 할 신호라 함께 싣는다
     * @param detections      이 사진의 검출. 손상이 없는 정상 사진은 <b>빈 목록</b>이다
     *                        ({@code CallbackImageResult} 주석). 원문이 {@code null} 이어도 같다
     */
    public record AnalysisImage(
            Long imageId,
            String angleCode,
            boolean excluded,
            String exclusionReason,
            List<Detection> detections) {
    }

    /**
     * 검출 한 건. <b>공유 계약 {@code $defs.detection} 에서 판정에 필요한 값만 평탄화했다.</b>
     *
     * <p>원문을 통째로 내보내지 않은 이유는 둘이다.
     *
     * <ol>
     *   <li>{@code geometry}(폴리곤)가 응답의 대부분인데 <b>검수 화면은 그림을 그리지 않는다</b>.
     *       좌표가 필요하면 사용자용 분석 결과 조회가 이미 원문을 준다</li>
     *   <li>구조가 DDL 로 강제되지 않는 값이라, 통째로 흘리면 계약이 바뀔 때 무엇이 나가는지
     *       아무도 모르는 채 바뀐다</li>
     * </ol>
     *
     * <p>대신 <b>평탄화가 계약을 조용히 놓치는 위험</b>은 테스트가 막는다 —
     * {@code AccidentReviewDetectionTest} 가 {@code shared/vision/common_schema.json} 을 읽어
     * 여기서 쓰는 키 이름이 아직 있는지 확인한다. 이름이 바뀌면 값이 {@code null} 로 조용히
     * 비는 대신 테스트가 깨진다.
     *
     * @param partCode         {@code null} 일 수 있다 — 부위를 못 찾은 검출이다
     * @param partConfidence   부위 인식 신뢰도. 계약의 {@code confidence.part}. {@code null} 가능
     * @param damageConfidence 손상 인식 신뢰도. 계약의 {@code confidence.damage}. {@code null} 가능
     */
    public record Detection(
            String detectionId,
            String partCode,
            String damageType,
            String pairStatus,
            String searchability,
            BigDecimal partConfidence,
            BigDecimal damageConfidence) {
    }

    /**
     * 체크리스트 항목 한 줄. <b>사용자가 손댄 흔적이 여기 있다.</b>
     *
     * <p>{@code source} 가 {@code USER} 면 사용자가 직접 넣은 항목이고({@code S15P21A307-485}),
     * {@code isChecked}·{@code memo} 는 출처와 무관하게 사용자가 남긴 것이다({@code -483}).
     *
     * <p>모든 항목을 준다 — 한 사고의 체크리스트는 <b>AI 최대 10 + 공통 6</b> 이라 잘라 줄 크기가
     * 아니고, 관리자는 "AI 가 뭘 제안했고 사용자가 무엇을 했나" 를 나란히 봐야 한다.
     */
    public record ChecklistItem(
            Long itemId,
            String source,
            String content,
            boolean checked,
            String memo,
            short displayOrder) {
    }
}
