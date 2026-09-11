package com.ssafy.a307.admin.service;

import com.ssafy.a307.admin.AdminErrorCode;
import com.ssafy.a307.admin.AdminOperationException;
import com.ssafy.a307.admin.dto.AdminPageRequest;
import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.admin.dto.PartCodeAdminResponse;
import com.ssafy.a307.admin.dto.PartCodeCreateRequest;
import com.ssafy.a307.admin.dto.PartCodeUpdateRequest;
import com.ssafy.a307.admin.dto.StatusUpdateRequest;
import com.ssafy.a307.audit.entity.AuditTargetType;
import com.ssafy.a307.audit.service.AuditLogService;
import com.ssafy.a307.estimatevalidation.entity.PartCode;
import com.ssafy.a307.estimatevalidation.entity.PartCodeScope;
import com.ssafy.a307.estimatevalidation.repository.PartCodeRepository;
import com.ssafy.a307.estimatevalidation.repository.PartNameMappingRepository;
import com.ssafy.a307.estimatevalidation.repository.RepairMethodRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 부품 코드 마스터 관리.
 *
 * <p><b>물리 삭제 API 가 없다.</b> {@code damaged_part}·{@code repair_case_item}·
 * {@code repair_case_roi_embedding}·{@code repair_cost_stat}·{@code estimate_validation_item}·
 * {@code part_name_mapping} 이 이 코드 문자열을 들고 있고 일부는 {@code ON DELETE RESTRICT} 다.
 * 지우면 과거 분석·견적·통계가 무엇을 가리켰는지 알 수 없게 된다.
 *
 * <p><b>비활성화에는 조건이 있다.</b> 활성 수리 방식 규칙이 그 부품을 지목하고 있으면 409 다 —
 * 규칙이 존재하지 않는 부품을 가리키게 두면 결정 경로가 조용히 비어 버린다.
 */
@Service
@RequiredArgsConstructor
public class AdminPartCodeService {

    private static final Set<String> SORTABLE =
            Set.of("partCode", "nameKo", "layoutZone", "displayOrder");
    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.asc("displayOrder"), Sort.Order.asc("partCode"));

    private final PartCodeRepository repository;
    private final PartNameMappingRepository mappingRepository;
    private final RepairMethodRuleRepository ruleRepository;
    private final AuditLogService auditLog;

    @Transactional(readOnly = true)
    public AdminPageResponse<PartCodeAdminResponse> search(
            String keyword, String layoutZone, PartCodeScope scope, Boolean active,
            Integer page, Integer size, String sort) {

        return AdminPageResponse.of(
                repository.searchForAdmin(blankToNull(keyword), blankToNull(layoutZone), scope, active,
                        AdminPageRequest.of(page, size, sort, DEFAULT_SORT, SORTABLE)),
                code -> PartCodeAdminResponse.from(
                        code, mappingRepository.countByPartCode_PartCode(code.getPartCode())));
    }

    @Transactional(readOnly = true)
    public PartCodeAdminResponse findOne(String partCode) {
        PartCode code = load(partCode);
        return PartCodeAdminResponse.from(code, mappingRepository.countByPartCode_PartCode(partCode));
    }

    @Transactional
    public PartCodeAdminResponse create(PartCodeCreateRequest request) {
        if (repository.existsById(request.partCode())) {
            throw new AdminOperationException(AdminErrorCode.DUPLICATE_PART_CODE,
                    "이미 등록된 부품 코드입니다. (%s)".formatted(request.partCode()));
        }
        PartCode saved = repository.saveAndFlush(PartCode.register(
                request.partCode(), request.nameKo(), request.layoutZone(),
                request.displayOrder().shortValue(), request.codeScope()));

        PartCodeAdminResponse after = PartCodeAdminResponse.from(saved, 0);
        auditLog.created(AuditTargetType.PART_CODE, saved.getPartCode(), after);
        return after;
    }

    @Transactional
    public PartCodeAdminResponse update(String partCode, PartCodeUpdateRequest request) {
        PartCode code = load(partCode);
        requireCurrentVersion(code.getVersion(), request.version());

        long mappings = mappingRepository.countByPartCode_PartCode(partCode);
        PartCodeAdminResponse before = PartCodeAdminResponse.from(code, mappings);
        code.modify(request.nameKo(), request.layoutZone(),
                request.displayOrder().shortValue(), request.codeScope());
        repository.flush();

        PartCodeAdminResponse after = PartCodeAdminResponse.from(code, mappings);
        auditLog.updated(AuditTargetType.PART_CODE, partCode, before, after);
        return after;
    }

    @Transactional
    public PartCodeAdminResponse changeStatus(String partCode, StatusUpdateRequest request) {
        PartCode code = load(partCode);
        requireCurrentVersion(code.getVersion(), request.version());

        // 같은 상태를 다시 요청하면 아무것도 하지 않는다. 이력에 "껐다" 가 두 번 남으면
        // 두 번째는 거짓이 되고, 언제 실제로 꺼졌는지 찾을 수 없게 된다. 엔티티를 건드리지
        // 않으므로 version 도 오르지 않아 남의 화면이 괜히 버전 충돌을 만나지 않는다.
        if (code.isActive() == request.active()) {
            return PartCodeAdminResponse.from(code, mappingRepository.countByPartCode_PartCode(partCode));
        }

        if (!request.active() && ruleRepository.existsByPartCodeAndActiveTrue(partCode)) {
            throw new AdminOperationException(AdminErrorCode.REFERENCED_BY_ACTIVE_RULE,
                    "활성 수리 방식 규칙이 이 부품을 참조하고 있습니다. 규칙을 먼저 변경해 주세요. (%s)"
                            .formatted(partCode));
        }

        long mappings = mappingRepository.countByPartCode_PartCode(partCode);
        PartCodeAdminResponse before = PartCodeAdminResponse.from(code, mappings);
        code.changeStatus(request.active());
        repository.flush();

        PartCodeAdminResponse after = PartCodeAdminResponse.from(code, mappings);
        auditLog.statusChanged(AuditTargetType.PART_CODE, partCode, before, after, request.active());
        return after;
    }

    private PartCode load(String partCode) {
        return repository.findById(partCode)
                .orElseThrow(() -> AdminOperationException.notFound("부품 코드"));
    }

    private void requireCurrentVersion(long current, Long requested) {
        if (requested == null || current != requested) {
            throw new AdminOperationException(AdminErrorCode.VERSION_CONFLICT,
                    "다른 관리자가 먼저 변경했습니다. 다시 조회한 뒤 시도해 주세요. (현재 version %d)"
                            .formatted(current));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
