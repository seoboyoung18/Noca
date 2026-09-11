package com.ssafy.a307.admin.controller;

import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.admin.dto.RepairMethodRuleCreateRequest;
import com.ssafy.a307.admin.dto.RepairMethodRuleResponse;
import com.ssafy.a307.admin.dto.RepairMethodRuleUpdateRequest;
import com.ssafy.a307.admin.dto.StatusUpdateRequest;
import com.ssafy.a307.admin.service.AdminRepairMethodRuleService;
import com.ssafy.a307.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 심각도 → 수리 방식 규칙 관리.
 *
 * <p><b>{@code /api/admin/**} 은 {@code SecurityConfig} 가 {@code hasRole("ADMIN")} 으로 막는다.</b>
 * 비로그인은 401, 일반 {@code USER} 는 공통 오류 봉투의 403 이다.
 *
 * <p><b>관리자 ID 를 요청으로 받지 않는다.</b> 어떤 DTO 에도 actor 필드가 없고, 감사 로그의
 * 행위자는 {@code CurrentMemberProvider} 가 세션에서 꺼낸다.
 * <p><b>⚠️ 이 규칙을 읽는 분석 파이프라인이 아직 없다.</b> 결정 경계
 * ({@code AdminRepairMethodRuleService#decide})는 구현돼 있고 통합 테스트가 지키지만,
 * {@code damaged_part} 를 만드는 파손 검출 코드가 이 저장소에 없어 운영 호출 경로는 비어 있다.
 */
@RestController
@RequestMapping("/api/admin/repair-method-rules")
@RequiredArgsConstructor
public class AdminRepairMethodRuleController {

    private final AdminRepairMethodRuleService service;

    @GetMapping
    public ApiResponse<AdminPageResponse<RepairMethodRuleResponse>> search(
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {

        return ApiResponse.of(service.search(active, page, size, sort));
    }

    @GetMapping("/{ruleId}")
    public ApiResponse<RepairMethodRuleResponse> findOne(@PathVariable Long ruleId) {
        return ApiResponse.of(service.findOne(ruleId));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RepairMethodRuleResponse>> create(
            @Valid @RequestBody RepairMethodRuleCreateRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(service.create(request)));
    }

    @PatchMapping("/{ruleId}")
    public ApiResponse<RepairMethodRuleResponse> update(
            @PathVariable Long ruleId, @Valid @RequestBody RepairMethodRuleUpdateRequest request) {

        return ApiResponse.of(service.update(ruleId, request));
    }

    @PatchMapping("/{ruleId}/status")
    public ApiResponse<RepairMethodRuleResponse> changeStatus(
            @PathVariable Long ruleId, @Valid @RequestBody StatusUpdateRequest request) {

        return ApiResponse.of(service.changeStatus(ruleId, request));
    }
}
