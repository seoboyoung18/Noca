package com.ssafy.a307.repaircase.dto;

import com.ssafy.a307.repaircase.repository.SimilarCaseRepository.SimilarCaseView;

/**
 * 유사 사례 한 건.
 *
 * @param repairYear 견적서 입고일자의 연도({@code repair_case.repair_year}).
 *                   원천에 날짜가 없거나 읽지 못한 사례는 {@code null} 이다
 * @param partTotal  이 사례에서 <b>같은 부위에</b> 든 비용. 부위 항목이 없으면 0 이다
 * @param imageUrl   대표 이미지 조회 URL. <b>지금은 {@code original} 이다</b> —
 *                   썸네일이 생기면 이 필드의 값만 바뀌고 계약은 그대로다.
 *                   저장소가 없거나 서명이 실패하면 {@code null} 이고, 그 건만 이미지가 빈다
 */
public record SimilarCaseItemResponse(
        Long caseId,
        String manufacturer,
        String modelName,
        String carClass,
        Short repairYear,
        Long partTotal,
        String imageUrl) {

    public static SimilarCaseItemResponse of(SimilarCaseView view, String imageUrl) {
        return new SimilarCaseItemResponse(
                view.getCaseId(),
                view.getManufacturer(),
                view.getModelName(),
                view.getCarClass(),
                view.getRepairYear(),
                view.getPartTotal(),
                imageUrl);
    }
}
