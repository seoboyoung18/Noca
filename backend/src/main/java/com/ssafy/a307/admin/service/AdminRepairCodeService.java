package com.ssafy.a307.admin.service;

import com.ssafy.a307.admin.AdminErrorCode;
import com.ssafy.a307.admin.AdminOperationException;
import com.ssafy.a307.admin.dto.RepairCodeAdminResponse;
import com.ssafy.a307.admin.dto.RepairCodeUpdateRequest;
import com.ssafy.a307.admin.dto.StatusUpdateRequest;
import com.ssafy.a307.audit.entity.AuditTargetType;
import com.ssafy.a307.audit.service.AuditLogService;
import com.ssafy.a307.estimatevalidation.entity.RepairCode;
import com.ssafy.a307.estimatevalidation.entity.RepairCodeType;
import com.ssafy.a307.estimatevalidation.repository.RepairCodeRepository;
import com.ssafy.a307.estimatevalidation.repository.RepairMethodRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 수리 방식·손상 유형 canonical code 의 표시층 관리.
 *
 * <p><b>등록·삭제 API 가 없다.</b> 코드 집합은 {@code StandardRepairMethod} enum 과
 * {@code damaged_part}·{@code estimate_item}·{@code repair_cost_stat} 의 DDL CHECK 가 함께
 * 고정한다. 관리자가 행을 추가하면 DB 만 늘고 계산은 그 값을 모른다 — 조용히 죽은 코드가 된다.
 * 새 canonical code 는 enum·CHECK·계산 로직을 함께 바꾸는 별도 작업이다.
 *
 * <p>바꿀 수 있는 것은 <b>표시명 · 표시 순서 · 활성 상태</b> 뿐이고, 이 제한은 API 와 FE
 * 문서에 그대로 드러난다.
 */
@Service
@RequiredArgsConstructor
public class AdminRepairCodeService {

    private final RepairCodeRepository repository;
    private final RepairMethodRuleRepository ruleRepository;
    private final AuditLogService auditLog;

    /** 행이 8개뿐이라 페이지네이션하지 않는다. 늘어나지 않는 표다. */
    @Transactional(readOnly = true)
    public List<RepairCodeAdminResponse> findAll(RepairCodeType codeType) {
        List<RepairCode> codes = codeType == null
                ? repository.findAllByOrderByCodeTypeAscDisplayOrderAsc()
                : repository.findByCodeTypeOrderByDisplayOrderAscCodeAsc(codeType);
        return codes.stream().map(RepairCodeAdminResponse::from).toList();
    }

    @Transactional
    public RepairCodeAdminResponse update(RepairCodeType codeType, String code,
                                          RepairCodeUpdateRequest request) {
        RepairCode entity = load(codeType, code);
        requireCurrentVersion(entity.getVersion(), request.version());

        RepairCodeAdminResponse before = RepairCodeAdminResponse.from(entity);
        entity.modify(request.displayName(), request.displayOrder().shortValue());
        repository.flush();

        RepairCodeAdminResponse after = RepairCodeAdminResponse.from(entity);
        auditLog.updated(AuditTargetType.REPAIR_CODE, targetId(codeType, code), before, after);
        return after;
    }

    @Transactional
    public RepairCodeAdminResponse changeStatus(RepairCodeType codeType, String code,
                                                StatusUpdateRequest request) {
        RepairCode entity = load(codeType, code);
        requireCurrentVersion(entity.getVersion(), request.version());

        // 같은 상태를 다시 요청하면 아무것도 하지 않는다. 이력에 "껐다" 가 두 번 남으면
        // 두 번째는 거짓이 되고, 언제 실제로 꺼졌는지 찾을 수 없게 된다. 엔티티를 건드리지
        // 않으므로 version 도 오르지 않아 남의 화면이 괜히 버전 충돌을 만나지 않는다.
        if (entity.isActive() == request.active()) {
            return RepairCodeAdminResponse.from(entity);
        }

        if (!request.active()) {
            requireNotUsedByActiveRule(codeType, code);
        }

        RepairCodeAdminResponse before = RepairCodeAdminResponse.from(entity);
        entity.changeStatus(request.active());
        repository.flush();

        RepairCodeAdminResponse after = RepairCodeAdminResponse.from(entity);
        auditLog.statusChanged(AuditTargetType.REPAIR_CODE, targetId(codeType, code),
                before, after, request.active());
        return after;
    }

    /**
     * 활성 규칙이 쓰고 있는 코드는 끌 수 없다.
     * <p>
     * 끄면 규칙은 살아 있는데 그 규칙이 가리키는 손상 유형·수리 방식이 목록에서 사라진다.
     * 관리자가 먼저 규칙을 바꾸게 만드는 편이 낫다 — 어느 쪽을 먼저 해야 하는지 오류 메시지가 말해 준다.
     */
    private void requireNotUsedByActiveRule(RepairCodeType codeType, String code) {
        boolean used = codeType == RepairCodeType.REPAIR_METHOD
                ? ruleRepository.existsByRepairMethodAndActiveTrue(code)
                : ruleRepository.existsByDamageTypeAndActiveTrue(code);
        if (used) {
            throw new AdminOperationException(AdminErrorCode.REFERENCED_BY_ACTIVE_RULE,
                    "활성 수리 방식 규칙이 이 코드를 사용하고 있습니다. 규칙을 먼저 변경해 주세요. (%s)"
                            .formatted(code));
        }
    }

    private RepairCode load(RepairCodeType codeType, String code) {
        return repository.findByCodeTypeAndCode(codeType, code)
                .orElseThrow(() -> AdminOperationException.notFound("코드"));
    }

    private static String targetId(RepairCodeType codeType, String code) {
        return codeType.name() + ":" + code;
    }

    private void requireCurrentVersion(long current, Long requested) {
        if (requested == null || current != requested) {
            throw new AdminOperationException(AdminErrorCode.VERSION_CONFLICT,
                    "다른 관리자가 먼저 변경했습니다. 다시 조회한 뒤 시도해 주세요. (현재 version %d)"
                            .formatted(current));
        }
    }
}
