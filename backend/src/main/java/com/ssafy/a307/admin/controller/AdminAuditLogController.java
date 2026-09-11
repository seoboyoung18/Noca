package com.ssafy.a307.admin.controller;

import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.admin.dto.AuditLogResponse;
import com.ssafy.a307.admin.service.AdminAuditLogService;
import com.ssafy.a307.audit.entity.AuditActionType;
import com.ssafy.a307.audit.entity.AuditTargetType;
import com.ssafy.a307.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 관리자 변경 이력 조회.
 *
 * <p><b>{@code /api/admin/**} 은 {@code SecurityConfig} 가 {@code hasRole("ADMIN")} 으로 막는다.</b>
 * 비로그인은 401, 일반 {@code USER} 는 공통 오류 봉투의 403 이다.
 *
 * <p><b>관리자 ID 를 요청으로 받지 않는다.</b> 어떤 DTO 에도 actor 필드가 없고, 감사 로그의
 * 행위자는 {@code CurrentMemberProvider} 가 세션에서 꺼낸다.
 * <p><b>수정·삭제 API 가 없다.</b> 고칠 수 있는 이력은 이력이 아니다.
 */
@RestController
@RequestMapping("/api/admin/audit-logs")
@RequiredArgsConstructor
public class AdminAuditLogController {

    private final AdminAuditLogService service;

    /**
     * @param from {@code 2026-09-10T00:00:00Z} 형식. 이상(포함)
     * @param to   같은 형식. <b>미만(제외)</b> — 하루치를 볼 때 다음 날 00:00 을 주면 된다
     */
    @GetMapping
    public ApiResponse<AdminPageResponse<AuditLogResponse>> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Long actorMemberId,
            @RequestParam(required = false) AuditActionType actionType,
            @RequestParam(required = false) AuditTargetType targetType,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {

        return ApiResponse.of(service.search(
                from, to, actorMemberId, actionType, targetType, targetId, page, size, sort));
    }
}
