package com.ssafy.a307.admin.controller;

import com.ssafy.a307.admin.dto.RepairCodeAdminResponse;
import com.ssafy.a307.admin.dto.RepairCodeUpdateRequest;
import com.ssafy.a307.admin.dto.StatusUpdateRequest;
import com.ssafy.a307.admin.service.AdminRepairCodeService;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.estimatevalidation.entity.RepairCodeType;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 수리 방식·손상 유형 canonical code 의 표시층 관리.
 *
 * <p><b>{@code /api/admin/**} 은 {@code SecurityConfig} 가 {@code hasRole("ADMIN")} 으로 막는다.</b>
 * 비로그인은 401, 일반 {@code USER} 는 공통 오류 봉투의 403 이다.
 *
 * <p><b>관리자 ID 를 요청으로 받지 않는다.</b> 어떤 DTO 에도 actor 필드가 없고, 감사 로그의
 * 행위자는 {@code CurrentMemberProvider} 가 세션에서 꺼낸다.
 * <p><b>등록·삭제 API 가 없다.</b> 코드 집합은 Java enum 과 DDL CHECK 가 함께 고정한다 —
 * 행만 늘리면 계산이 그 값을 몰라 조용히 죽은 코드가 된다. 바꿀 수 있는 것은
 * <b>표시명 · 표시 순서 · 활성 상태</b> 뿐이다.
 */
@RestController
@RequestMapping("/api/admin/repair-codes")
@RequiredArgsConstructor
public class AdminRepairCodeController {

    private final AdminRepairCodeService service;

    /** 행이 8개뿐이라 페이지네이션하지 않는다. 늘어나지 않는 표다. */
    @GetMapping
    public ApiResponse<List<RepairCodeAdminResponse>> findAll(
            @RequestParam(required = false) RepairCodeType codeType) {

        return ApiResponse.of(service.findAll(codeType));
    }

    @PatchMapping("/{codeType}/{code}")
    public ApiResponse<RepairCodeAdminResponse> update(
            @PathVariable RepairCodeType codeType, @PathVariable String code,
            @Valid @RequestBody RepairCodeUpdateRequest request) {

        return ApiResponse.of(service.update(codeType, code, request));
    }

    @PatchMapping("/{codeType}/{code}/status")
    public ApiResponse<RepairCodeAdminResponse> changeStatus(
            @PathVariable RepairCodeType codeType, @PathVariable String code,
            @Valid @RequestBody StatusUpdateRequest request) {

        return ApiResponse.of(service.changeStatus(codeType, code, request));
    }
}
