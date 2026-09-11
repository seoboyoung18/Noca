package com.ssafy.a307.admin.controller;

import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.admin.dto.PartCodeAdminResponse;
import com.ssafy.a307.admin.dto.PartCodeCreateRequest;
import com.ssafy.a307.admin.dto.PartCodeUpdateRequest;
import com.ssafy.a307.admin.dto.StatusUpdateRequest;
import com.ssafy.a307.admin.service.AdminPartCodeService;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;
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
 * 부품 코드 마스터 관리. 코드 문자열은 등록 후 불변이고 물리 삭제 API 도 없다.
 *
 * <p><b>{@code /api/admin/**} 은 {@code SecurityConfig} 가 {@code hasRole("ADMIN")} 으로 막는다.</b>
 * 비로그인은 401, 일반 {@code USER} 는 공통 오류 봉투의 403 이다.
 *
 * <p><b>관리자 ID 를 요청으로 받지 않는다.</b> 어떤 DTO 에도 actor 필드가 없고, 감사 로그의
 * 행위자는 {@code CurrentMemberProvider} 가 세션에서 꺼낸다.
 */
@RestController
@RequestMapping("/api/admin/part-codes")
@RequiredArgsConstructor
public class AdminPartCodeController {

    private final AdminPartCodeService service;

    /**
     * @param scope {@code AI_LABEL} 이면 파손 검출 모델 라벨 32종, {@code EXTENDED} 면 견적 확장 코드.
     *              생략하면 둘 다 나온다
     */
    @GetMapping
    public ApiResponse<AdminPageResponse<PartCodeAdminResponse>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String layoutZone,
            @RequestParam(required = false) PartCodeScope scope,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {

        return ApiResponse.of(service.search(keyword, layoutZone, scope, active, page, size, sort));
    }

    @GetMapping("/{partCode}")
    public ApiResponse<PartCodeAdminResponse> findOne(@PathVariable String partCode) {
        return ApiResponse.of(service.findOne(partCode));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<PartCodeAdminResponse>> create(
            @Valid @RequestBody PartCodeCreateRequest request) {

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(service.create(request)));
    }

    @PatchMapping("/{partCode}")
    public ApiResponse<PartCodeAdminResponse> update(
            @PathVariable String partCode, @Valid @RequestBody PartCodeUpdateRequest request) {

        return ApiResponse.of(service.update(partCode, request));
    }

    @PatchMapping("/{partCode}/status")
    public ApiResponse<PartCodeAdminResponse> changeStatus(
            @PathVariable String partCode, @Valid @RequestBody StatusUpdateRequest request) {

        return ApiResponse.of(service.changeStatus(partCode, request));
    }
}
