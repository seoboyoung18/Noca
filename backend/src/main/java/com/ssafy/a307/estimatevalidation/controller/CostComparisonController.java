package com.ssafy.a307.estimatevalidation.controller;

import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import com.ssafy.a307.estimatevalidation.dto.CostComparisonResponse;
import com.ssafy.a307.estimatevalidation.service.CostComparisonService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accidents")
@RequiredArgsConstructor
public class CostComparisonController {

    private final CostComparisonService service;
    private final CurrentMemberProvider currentMemberProvider;

    @GetMapping("/{accidentId}/cost-comparison")
    public ApiResponse<CostComparisonResponse> compare(@PathVariable Long accidentId) {
        return ApiResponse.of(service.compare(currentMemberProvider.currentMemberId(), accidentId));
    }
}
