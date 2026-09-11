package com.ssafy.a307.repaircase.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 유사 사례 상세 (S15P21A307-241).
 *
 * <p><b>내부 식별자는 내보내지 않는다.</b> {@code external_ref}(데이터셋 원본 ID),
 * {@code source_image_ref}(데이터셋 경로), {@code storage_key}(S3 key) 는 개인정보는 아니지만
 * 화면에 쓸 곳이 없고, 원천 추적 경로를 밖으로 여는 값이다.
 *
 * @param repairYear     견적서 입고일자의 연도. 원천에 날짜가 없으면 {@code null}
 * @param totalCost      정비소 청구 기준 총액 — AS 는 {@code total_cost}(총계),
 *                       SC 는 {@code claim_amount}(손해사정 전 청구액). 요구사항 48행의 통일 기준이다
 * @param parts          부위별 수리 내역. 부위 표시 순서({@code part_code.display_order})대로다
 * @param ancillaryItems 견인·구난 같은 부대 비용. 부품이 아니라 부위에 묶을 수 없다
 */
public record RepairCaseDetailResponse(
        Long caseId,
        String manufacturer,
        String modelName,
        String carClass,
        Short repairYear,
        Integer totalCost,
        List<Image> images,
        List<Part> parts,
        List<Item> ancillaryItems) {

    /** @param url 조회 URL. 저장소가 없거나 서명이 실패하면 {@code null} — 그 이미지만 빈다 */
    public record Image(String url) {
    }

    /**
     * @param partTotal 이 부위에 든 비용. 정비소 청구 기준이라 <b>불인정 항목도 합산한다</b> —
     *                  {@code totalCost} 와 같은 기준이다
     */
    public record Part(String partCode, String partNameKo, long partTotal, List<Item> items) {
    }

    /**
     * 견적서 한 행.
     *
     * @param workName    작업 원문(판금·교환·도장 등). 불인정 행은 원천이 작업을 덮어써 원래 작업을
     *                    복구할 수 없어 {@code "불인정"} 이다
     * @param notApproved 손해사정에서 불인정된 행. 화면 표시용이다 — 정비소가 청구한 금액이라
     *                    부위 합계에는 들어간다
     * @param hq          정비시간(HQ)
     */
    public record Item(
            String workCode,
            String workName,
            boolean notApproved,
            BigDecimal hq,
            Integer partCost,
            Integer paintMaterialCost,
            Integer laborCost,
            Integer itemTotal) {
    }
}
