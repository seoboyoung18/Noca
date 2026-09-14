package com.ssafy.a307.analysis.dto;

import com.ssafy.a307.analysis.entity.AnalysisJobStatus;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;

/**
 * {@code GET /api/accidents/{accidentId}/analysis/result} 응답 — 분석 결과 (S15P21A307-203).
 *
 * <p>진행 상태({@link AnalysisProgressResponse})와 <b>같은 사고 기준 경로</b>다. 화면이 쥐고
 * 있는 것은 {@code accidentId} 이고 {@code jobId} 는 서버가 정하는 값이라, 재분석으로 작업이
 * 새로 생겨도 화면이 URL 을 바꾸지 않는다.
 *
 * <h2>무엇에 쓰나</h2>
 *
 * <p>화면 11(예상 견적·손상분석)이 <b>사진 위에 부위별 콜아웃을 그리는 데</b> 쓴다. 금액과
 * 항목은 {@code GET /api/estimates/{id}} 가 이미 주므로 여기서 다시 주지 않는다 — 같은 값을
 * 두 곳에서 내려보내면 언젠가 어긋난다.
 *
 * <h2>사진 URL 을 주지 않는다</h2>
 *
 * <p>사진은 사고 이미지 조회 API 가 presigned URL 로 이미 준다. 여기서 다시 서명하면 발급
 * 정책(유효시간·노출 variant)이 두 곳에 생긴다. 화면은 {@link Image#imageId} 로 두 응답을
 * 맞춘다.
 *
 * <h2>좌표를 서버가 해석하지 않는다</h2>
 *
 * <p>{@link Image#detections} 는 AI 가 보낸 배열 <b>원문 그대로</b>다. 계약이 "키 이름·구조를
 * 바꾸지 말고 그대로 넣습니다. 프론트가 견적을 다시 열 때 이 값으로 다시 그리므로, 필드를
 * 덜어내면 그리기가 깨집니다" 라고 요구한다. 자바 타입으로 매핑하면 매핑하지 않은 필드가
 * 조용히 사라지므로, 저장할 때와 같은 이유로 읽을 때도 통과시킨다.
 *
 * <p>안에 들어 있는 것(계약 ⑥ 기준): {@code detectionId} · {@code partCode} ·
 * {@code damageType} · {@code pairStatus} · {@code searchability} · {@code confidence} ·
 * {@code geometry}({@code coordinateSystem}·{@code bboxFormat}·{@code bbox}·{@code polygons}·
 * {@code areaPx}·{@code areaRatio}).
 *
 * <h2>빈 상태</h2>
 *
 * <p>아직 분석을 요청하지 않은 사고는 <b>오류가 아니라 빈 상태 200</b> 이다. 404 로 내면
 * 화면이 "없는 사고" 와 "분석 전" 을 구분하지 못한다 — 진행 상태 API 와 같은 판단이다.
 *
 * @param status 작업 상태. {@code COMPLETED} 가 아니면 {@code parts}·{@code images} 가 비어
 *               있을 수 있다. 화면은 이 값을 먼저 보고 그릴지 기다릴지 정한다
 */
public record AnalysisResultResponse(
        Long jobId,
        AnalysisJobStatus status,
        String failureReason,
        List<Part> parts,
        List<Image> images) {

    public AnalysisResultResponse {
        parts = parts == null ? List.of() : List.copyOf(parts);
        images = images == null ? List.of() : List.copyOf(images);
    }

    /** 분석을 아직 요청하지 않은 사고. */
    public static AnalysisResultResponse notRequested() {
        return new AnalysisResultResponse(null, null, null, List.of(), List.of());
    }

    /**
     * 검출된 부품 하나. 정렬은 {@code part_code.display_order} 순이며 견적 항목과 같은 기준이다.
     *
     * @param repairMethod            {@code null} 일 수 있다 — 후보가 둘인 손상은
     *                                S15P21A307-196 규칙이 서기 전까지 확정하지 않는다
     * @param repairMethodDisplayName 서버가 붙인 한글 표시명. FE 가 4종을 하드코딩하지 않게 한다.
     *                                방식이 미정이면 {@code null}
     * @param confidence              0~1. 낮은 값을 화면이 어떻게 표시할지는 FE 가 정한다 —
     *                                임계값은 견적 항목 쪽(S15P21A307-205)의 것이고 층이 다르다
     */
    public record Part(
            String partCode,
            String partNameKo,
            String layoutZone,
            String damageType,
            String repairMethod,
            String repairMethodDisplayName,
            BigDecimal confidence) {
    }

    /**
     * 사진 한 장의 결과.
     *
     * @param width           <b>원본</b> 가로 픽셀. {@code detections} 의 좌표가 이 크기를 기준으로
     *                        한다. 화면이 {@code resized} 를 띄운다면 비율로 환산해야 한다.
     *                        업로드 전처리가 치수를 얻지 못했으면 {@code null} 이고,
     *                        그때는 <b>환산할 수 없으므로 그리지 않는 편이 낫다</b>
     * @param excluded        분석에서 빠진 사진인가. 빠진 사진도 목록에 남긴다 —
     *                        화면이 사유와 함께 보여 줘야 사용자가 다시 찍을지 판단한다
     * @param exclusionReason {@code NOT_VEHICLE} · {@code RATIO_BELOW_THRESHOLD}. 코드다
     * @param detections      AI 원문. 손상이 없는 정상 사진은 빈 배열이다
     */
    public record Image(
            Long imageId,
            String angleCode,
            Integer width,
            Integer height,
            boolean excluded,
            String exclusionReason,
            JsonNode detections) {
    }
}
