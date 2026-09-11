package com.ssafy.a307.repaircase.controller;

import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.repaircase.dto.RepairCaseDetailResponse;
import com.ssafy.a307.repaircase.service.RepairCaseDetailService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 유사 사례 상세 조회 (S15P21A307-241).
 *
 * <p>로그인만 요구한다. 사례는 공개 데이터셋이라 소유자가 없다 — 이유는
 * {@link RepairCaseDetailService} 참고.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class RepairCaseController {

    private final RepairCaseDetailService repairCaseDetailService;

    @GetMapping("/repair-cases/{caseId}")
    public ApiResponse<RepairCaseDetailResponse> detail(@PathVariable Long caseId) {
        return ApiResponse.of(repairCaseDetailService.detail(caseId));
    }
}
