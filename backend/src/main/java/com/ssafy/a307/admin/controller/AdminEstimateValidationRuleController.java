package com.ssafy.a307.admin.controller;

import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.admin.dto.EstimateValidationRuleResponse;
import com.ssafy.a307.admin.dto.EstimateValidationRuleUpdateRequest;
import com.ssafy.a307.admin.service.AdminEstimateValidationRuleService;
import com.ssafy.a307.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 견적서 이상 탐지 임계값 관리.
 *
 * <p><b>{@code /api/admin/**} 은 {@code SecurityConfig} 가 {@code hasRole("ADMIN")} 으로 막는다.</b>
 * 비로그인은 401, 일반 {@code USER} 는 공통 오류 봉투의 403 이다.
 *
 * <p><b>관리자 ID 를 요청으로 받지 않는다.</b> 어떤 DTO 에도 actor 필드가 없고, 감사 로그의
 * 행위자는 {@code CurrentMemberProvider} 가 세션에서 꺼낸다.
 * <p><b>{@code PATCH} 지만 기존 값을 고치지 않는다.</b> 다음 버전을 만들어 즉시 현재 규칙으로
 * 삼는다 — 과거 검증이 어떤 기준으로 판정됐는지 되짚을 수 있어야 하기 때문이다.
 */
@RestController
@RequestMapping("/api/admin/estimate-validation-rules")
@RequiredArgsConstructor
public class AdminEstimateValidationRuleController {

    private final AdminEstimateValidationRuleService service;

    /** 지금 적용 중인 규칙. 수정할 때 이 응답의 {@code ruleVersion} 을 {@code baseVersion} 으로 보낸다. */
    @GetMapping("/current")
    public ApiResponse<EstimateValidationRuleResponse> current() {
        return ApiResponse.of(service.current());
    }

    /** 버전 이력. 최신이 앞이다. */
    @GetMapping("/history")
    public ApiResponse<AdminPageResponse<EstimateValidationRuleResponse>> history(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {

        return ApiResponse.of(service.history(page, size, sort));
    }

    /** 새 버전을 만들어 즉시 활성화한다. 다음 검증부터 반영되고 과거 결과는 바뀌지 않는다. */
    @PatchMapping("/current")
    public ApiResponse<EstimateValidationRuleResponse> update(
            @Valid @RequestBody EstimateValidationRuleUpdateRequest request) {

        return ApiResponse.of(service.createNextVersion(request));
    }
}
