package com.ssafy.a307.estimate.dto;

import com.ssafy.a307.estimatevalidation.dto.ValidationResultResponse;

import java.time.Instant;
import java.util.List;

/**
 * 사고 분석 견적 리포트 (S15P21A307-336·337·338).
 *
 * <p><b>저장하지 않고 매번 조립한다.</b> 이미 저장된 사실을 모으는 것이라 가볍고, 리포트에 버전을
 * 두지 않는다 — 견적 버전이 곧 리포트 버전이다. 파일 이력은 PDF({@code estimate_report})가 갖는다.
 *
 * <p><b>새로 계산한 값이 없다.</b> 견적·근거·검증은 각 조회 API 의 응답을 그대로 담는다. 리포트와
 * 화면이 다른 숫자를 보이면 어느 쪽도 믿을 수 없게 된다.
 *
 * @param images      분석에 쓰인 사진. 분석에서 제외된 사진은 없다
 * @param validation  이 견적에 연결된 견적서 검증 결과. <b>없으면 null</b> — 화면·PDF 는 검증 섹션을
 *                    그리지 않는다(S15P21A307-338)
 * @param legalNotice 고지 문구. 견적서 검증 화면·PDF 와 같은 문장이다(S15P21A307-289)
 */
public record EstimateReportResponse(
        Vehicle vehicle,
        Accident accident,
        List<Image> images,
        EstimateResponse estimate,
        EstimateBasisResponse basis,
        ValidationResultResponse validation,
        String legalNotice,
        Instant generatedAt) {

    /** 사고 접수 당시 차량. 이후 차량을 고치거나 지워도 바뀌지 않는다. */
    public record Vehicle(
            String manufacturer,
            String modelName,
            String vehicleType,
            String carClass,
            Short modelYear) {
    }

    public record Accident(Long accidentId, Instant createdAt) {
    }

    /**
     * @param overlayUrl 파손 부위를 표시한 분석 이미지의 조회 URL. <b>없거나 서명이 실패하면 null</b> —
     *                   화면·PDF 는 그 자리에 "분석 이미지 없음" 을 표시한다
     */
    public record Image(Long imageId, String angleCode, String overlayUrl) {
    }
}
