package com.ssafy.a307.estimate.controller;

import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.estimate.dto.EstimateReportResponse;
import com.ssafy.a307.estimate.service.EstimateReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사고 분석 견적 리포트 (S15P21A307-337).
 *
 * <p><b>견적 기준 경로다.</b> PDF 가 {@code /api/estimates/{id}/pdf} 라 "이 견적의 리포트 → 그 리포트의
 * PDF" 로 1:1 이 된다. 사고 기준으로 두면 재분석으로 생긴 견적 버전 중 어느 것인지 다시 정해야 한다.
 * 사고 정보는 리포트 안에 담긴다.
 *
 * <p><b>동기 GET 이다.</b> 저장된 값을 모으기만 하므로 무거운 작업이 없다. 렌더링이 무거운 PDF 만
 * 비동기(202)다.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class EstimateReportController {

    private final EstimateReportService estimateReportService;

    @GetMapping("/estimates/{estimateId}/report")
    public ApiResponse<EstimateReportResponse> report(@PathVariable Long estimateId,
                                                      @AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.of(estimateReportService.report(estimateId, principal.getMemberId()));
    }
}
