package com.ssafy.a307.admin.controller;

import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.admin.dto.AuditLogResponse;
import com.ssafy.a307.admin.dto.RuleOverviewResponse;
import com.ssafy.a307.admin.service.AdminAuditLogService;
import com.ssafy.a307.admin.service.AdminRuleOverviewService;
import com.ssafy.a307.audit.entity.AuditActionType;
import com.ssafy.a307.audit.entity.AuditTargetType;
import com.ssafy.a307.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * 규칙 관리 화면 전용 <b>읽기</b> 엔드포인트.
 *
 * <p><b>{@code /api/admin/**} 은 {@code SecurityConfig} 가 {@code hasRole("ADMIN")} 으로 막는다.</b>
 * 비로그인은 401, 일반 {@code USER} 는 공통 오류 봉투의 403 이다.
 *
 * <h2>왜 읽기만 있는가</h2>
 * 저장은 각 리소스의 원래 엔드포인트가 담당한다.
 *
 * <table border="1">
 *   <caption>쓰기는 여기가 아니다</caption>
 *   <tr><th>할 일</th><th>엔드포인트</th></tr>
 *   <tr><td>수리 방식 규칙 등록·수정·상태 변경</td>
 *       <td>{@code POST/PUT/PATCH /api/admin/repair-method-rules}</td></tr>
 *   <tr><td>이상 탐지 임계값 변경</td>
 *       <td>{@code PATCH /api/admin/estimate-validation-rules/current}</td></tr>
 * </table>
 *
 * <p>같은 자원에 쓰기 경로를 둘 만들면 검증과 감사 이력이 두 벌이 되고, 한쪽만 고치는 순간
 * 조용히 갈라진다. 화면이 편하려고 치르기에는 비싼 값이다. <b>읽기 합본만</b> 여기 둔다 —
 * 읽기는 여러 경로가 있어도 진실이 갈라지지 않는다.
 */
@RestController
@RequestMapping("/api/admin/rules")
@RequiredArgsConstructor
public class AdminRuleController {

    private final AdminRuleOverviewService overviewService;
    private final AdminAuditLogService auditLogService;

    /**
     * 화면이 처음 그릴 때 필요한 것 전부 — 수리 방식 규칙 목록, 현재 이상 탐지 임계값,
     * 입력 폼의 단위·경계·선택 가능한 코드 목록.
     *
     * <p>세 가지를 <b>한 읽기 트랜잭션</b>에서 읽는다. 나눠 부르면 그 사이 다른 관리자의
     * 저장이 끼어들어 화면이 서로 다른 시점의 값을 한 폼에 담을 수 있다.
     *
     * <p>규칙이 하나도 없으면 {@code repairMethodRules} 는 <b>빈 배열</b>이다 — 오류가 아니다.
     */
    @GetMapping("/overview")
    public ApiResponse<RuleOverviewResponse> overview() {
        return ApiResponse.of(overviewService.overview());
    }

    /**
     * 규칙 변경 이력. <b>최신순 고정</b>이고 규칙 두 종류만 본다.
     *
     * <p>{@code /api/admin/audit-logs} 와 무엇이 다른가 — 저쪽은 부품 코드·차량 모델·부품명
     * 매핑까지 전부 섞여 나온다. 규칙 화면에서 "내가 방금 바꾼 임계값" 을 찾으려고 수십 행을
     * 넘기게 하지 않으려고, 여기서는 {@code REPAIR_METHOD_RULE}·{@code ESTIMATE_VALIDATION_RULE}
     * 로 미리 좁힌다.
     *
     * @param targetType 둘 중 하나로 더 좁힌다. 생략하면 규칙 두 종류 모두.
     *                   규칙이 아닌 값을 주면 그 종류의 이력이 없으므로 빈 결과다
     */
    @GetMapping("/history")
    public ApiResponse<AdminPageResponse<AuditLogResponse>> history(
            @RequestParam(required = false) AuditTargetType targetType,
            @RequestParam(required = false) AuditActionType actionType,
            @RequestParam(required = false) Long actorMemberId,
            @RequestParam(required = false) String targetId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        Set<AuditTargetType> targets = targetType == null
                ? AdminAuditLogService.RULE_TARGETS
                : Set.of(targetType);

        return ApiResponse.of(auditLogService.search(
                null, null, actorMemberId, actionType, targets, targetId, page, size, null));
    }
}
