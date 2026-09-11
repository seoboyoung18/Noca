package com.ssafy.a307.admin.service;

import com.ssafy.a307.admin.AdminErrorCode;
import com.ssafy.a307.admin.AdminOperationException;
import com.ssafy.a307.admin.dto.AdminPageRequest;
import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.admin.dto.RepairMethodRuleCreateRequest;
import com.ssafy.a307.admin.dto.RepairMethodRuleResponse;
import com.ssafy.a307.admin.dto.RepairMethodRuleUpdateRequest;
import com.ssafy.a307.admin.dto.StatusUpdateRequest;
import com.ssafy.a307.audit.entity.AuditTargetType;
import com.ssafy.a307.audit.service.AuditLogService;
import com.ssafy.a307.estimatevalidation.domain.RepairMethodDecider;
import com.ssafy.a307.estimatevalidation.entity.RepairCodeType;
import com.ssafy.a307.estimatevalidation.entity.RepairMethodRule;
import com.ssafy.a307.estimatevalidation.repository.PartCodeRepository;
import com.ssafy.a307.estimatevalidation.repository.RepairCodeRepository;
import com.ssafy.a307.estimatevalidation.repository.RepairMethodRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 심각도 → 수리 방식 규칙 관리와 <b>결정 조회 계약</b>.
 *
 * <p>{@link #decide} 가 규칙의 유일한 소비 지점이다. 관리 API 와 결정이 같은 경계 규칙
 * ({@link RepairMethodRule#covers}·{@link RepairMethodRule#overlaps})을 공유해서, 등록할 때
 * 막은 겹침이 조회할 때 다르게 해석되는 일이 없다.
 *
 * <p><b>⚠️ 아직 이 결정을 부르는 분석 파이프라인이 없다.</b> {@code damaged_part} 를 쓰는
 * 파손 검출·견적 산정 코드가 이 저장소에 없어서({@code analysis_job} 계열 엔티티 0개),
 * {@link #decide} 는 <b>분석 서비스가 붙을 때 연결할 조회 계약</b>이다. 계약이 실제로 도는지는
 * 등록 → 결정 → 규칙 변경 → 재결정까지 도는 통합 테스트가 지킨다.
 */
@Service
@RequiredArgsConstructor
public class AdminRepairMethodRuleService {

    private static final Set<String> SORTABLE =
            Set.of("ruleId", "damageType", "priority", "severityMin", "createdAt");
    private static final Sort DEFAULT_SORT =
            Sort.by(Sort.Order.asc("damageType"), Sort.Order.desc("priority"), Sort.Order.asc("severityMin"));

    private final RepairMethodRuleRepository repository;
    private final RepairCodeRepository codeRepository;
    private final PartCodeRepository partCodeRepository;
    private final AuditLogService auditLog;

    private final RepairMethodDecider decider = new RepairMethodDecider();

    // ── 결정 경계 ──────────────────────────────────────────────────────────

    /**
     * 심각도 점수를 수리 방식으로 바꾼다. 활성 규칙만 본다.
     *
     * @return 맞는 규칙이 없으면 {@link Optional#empty()} — 기본값으로 덮지 않는다.
     *         근거 없는 수리 방식이 견적에 들어가는 것보다 "정하지 못했다" 가 낫다
     */
    @Transactional(readOnly = true)
    public Optional<String> decide(String damageType, String partCode, BigDecimal severity) {
        return decider.decide(repository.findCandidates(damageType, partCode), severity);
    }

    // ── 관리 ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AdminPageResponse<RepairMethodRuleResponse> search(
            Boolean active, Integer page, Integer size, String sort) {

        var pageable = AdminPageRequest.of(page, size, sort, DEFAULT_SORT, SORTABLE);
        Page<RepairMethodRule> found = active == null
                ? repository.findAll(pageable)
                : repository.findByActive(active, pageable);
        return AdminPageResponse.of(found, RepairMethodRuleResponse::from);
    }

    @Transactional(readOnly = true)
    public RepairMethodRuleResponse findOne(Long ruleId) {
        return RepairMethodRuleResponse.from(load(ruleId));
    }

    @Transactional
    public RepairMethodRuleResponse create(RepairMethodRuleCreateRequest request) {
        requireActiveCode(RepairCodeType.DAMAGE_TYPE, request.damageType());
        requireActiveCode(RepairCodeType.REPAIR_METHOD, request.repairMethod());
        requireActivePartCode(request.partCode());
        requireValidRange(request.severityMin(), request.severityMax());
        requireNoOverlap(request.damageType(), request.partCode(),
                request.severityMin(), request.severityMax(), request.maxInclusive(), null);

        RepairMethodRule saved = repository.saveAndFlush(rule(() -> RepairMethodRule.register(
                request.damageType(), request.partCode(),
                request.severityMin(), request.severityMax(), request.maxInclusive(),
                request.repairMethod(), request.priority().shortValue())));

        RepairMethodRuleResponse after = RepairMethodRuleResponse.from(saved);
        auditLog.created(AuditTargetType.REPAIR_METHOD_RULE, String.valueOf(saved.getRuleId()), after);
        return after;
    }

    @Transactional
    public RepairMethodRuleResponse update(Long ruleId, RepairMethodRuleUpdateRequest request) {
        RepairMethodRule rule = load(ruleId);
        requireCurrentVersion(rule.getVersion(), request.version());
        requireActiveCode(RepairCodeType.REPAIR_METHOD, request.repairMethod());
        requireValidRange(request.severityMin(), request.severityMax());
        requireNoOverlap(rule.getDamageType(), rule.getPartCode(),
                request.severityMin(), request.severityMax(), request.maxInclusive(), ruleId);

        RepairMethodRuleResponse before = RepairMethodRuleResponse.from(rule);
        run(() -> rule.modify(request.severityMin(), request.severityMax(), request.maxInclusive(),
                request.repairMethod(), request.priority().shortValue()));
        repository.flush();

        RepairMethodRuleResponse after = RepairMethodRuleResponse.from(rule);
        auditLog.updated(AuditTargetType.REPAIR_METHOD_RULE, String.valueOf(ruleId), before, after);
        return after;
    }

    @Transactional
    public RepairMethodRuleResponse changeStatus(Long ruleId, StatusUpdateRequest request) {
        RepairMethodRule rule = load(ruleId);
        requireCurrentVersion(rule.getVersion(), request.version());

        // 같은 상태를 다시 요청하면 아무것도 하지 않는다. 이력에 "껐다" 가 두 번 남으면
        // 두 번째는 거짓이 되고, 언제 실제로 꺼졌는지 찾을 수 없게 된다. 엔티티를 건드리지
        // 않으므로 version 도 오르지 않아 남의 화면이 괜히 버전 충돌을 만나지 않는다.
        if (rule.isActive() == request.active()) {
            return RepairMethodRuleResponse.from(rule);
        }

        if (request.active()) {
            // 다시 켜는 것은 "새로 쓸 수 있게 만드는" 일이라 등록과 같은 코드 검사를 받는다.
            // 코드 비활성화는 활성 규칙이 쓰는 코드만 막으므로, 규칙을 꺼 둔 사이 코드가 꺼졌을 수 있다.
            // 여기서 보지 않으면 드롭다운에 없는 코드를 돌려주는 활성 규칙이 생긴다.
            requireActiveCode(RepairCodeType.DAMAGE_TYPE, rule.getDamageType());
            requireActiveCode(RepairCodeType.REPAIR_METHOD, rule.getRepairMethod());
            requireActivePartCode(rule.getPartCode());
            // 다시 켤 때도 겹침을 본다 — 꺼져 있는 동안 그 구간을 덮는 규칙이 생겼을 수 있다.
            requireNoOverlap(rule.getDamageType(), rule.getPartCode(),
                    rule.getSeverityMin(), rule.getSeverityMax(), rule.isMaxInclusive(), ruleId);
        } else {
            requireNotLastActive(rule);
        }

        RepairMethodRuleResponse before = RepairMethodRuleResponse.from(rule);
        rule.changeStatus(request.active());
        repository.flush();

        RepairMethodRuleResponse after = RepairMethodRuleResponse.from(rule);
        auditLog.statusChanged(AuditTargetType.REPAIR_METHOD_RULE, String.valueOf(ruleId),
                before, after, request.active());
        return after;
    }

    // ── 검증 ──────────────────────────────────────────────────────────────

    private RepairMethodRule load(Long ruleId) {
        return repository.findById(ruleId)
                .orElseThrow(() -> AdminOperationException.notFound("수리 방식 규칙"));
    }

    /** canonical code 목록에 있고 활성이어야 한다. 표시층이 실제로 읽히는 지점이다. */
    private void requireActiveCode(RepairCodeType codeType, String code) {
        if (codeRepository.findByCodeTypeAndCode(codeType, code).isEmpty()) {
            throw new AdminOperationException(AdminErrorCode.UNKNOWN_CODE,
                    "알 수 없는 코드입니다. (%s %s)".formatted(codeType.name(), code));
        }
        if (!codeRepository.existsByCodeTypeAndCodeAndActiveTrue(codeType, code)) {
            throw new AdminOperationException(AdminErrorCode.INACTIVE_CODE,
                    "비활성 코드로는 규칙을 만들 수 없습니다. (%s %s)".formatted(codeType.name(), code));
        }
    }

    private void requireActivePartCode(String partCode) {
        if (partCode == null) return;
        var code = partCodeRepository.findById(partCode)
                .orElseThrow(() -> AdminOperationException.notFound("부품 코드"));
        if (!code.isActive()) {
            throw new AdminOperationException(AdminErrorCode.INACTIVE_CODE,
                    "비활성 부품 코드로는 규칙을 만들 수 없습니다. (%s)".formatted(partCode));
        }
    }

    /** DB CHECK 와 같은 조건을 미리 본다 — 제약 위반 예외보다 읽을 수 있는 메시지를 준다. */
    private void requireValidRange(BigDecimal min, BigDecimal max) {
        if (min.compareTo(max) >= 0) {
            throw new AdminOperationException(AdminErrorCode.INVALID_SEVERITY_RANGE,
                    "심각도 하한은 상한보다 작아야 합니다. (하한 %s, 상한 %s)".formatted(min, max));
        }
    }

    /**
     * 같은 적용 범위에서 활성 구간이 겹치면 막는다.
     * <p>
     * 겹치면 경계값 하나가 두 규칙에 잡혀 <b>우선순위에 따라 수리 방식이 달라진다</b> —
     * 같은 손상에 같은 점수인데 결과가 흔들린다.
     */
    private void requireNoOverlap(String damageType, String partCode,
                                  BigDecimal min, BigDecimal max, boolean maxInclusive,
                                  Long excludeRuleId) {
        List<RepairMethodRule> siblings = repository.findActiveInScope(damageType, partCode);
        for (RepairMethodRule sibling : siblings) {
            if (excludeRuleId != null && excludeRuleId.equals(sibling.getRuleId())) continue;
            if (sibling.overlaps(min, max, maxInclusive)) {
                throw new AdminOperationException(AdminErrorCode.OVERLAPPING_RULE_RANGE,
                        "같은 적용 범위에 겹치는 활성 규칙이 있습니다. (규칙 %d: %s ~ %s)"
                                .formatted(sibling.getRuleId(),
                                        sibling.getSeverityMin(), sibling.getSeverityMax()));
            }
        }
    }

    /**
     * 마지막 활성 규칙은 끌 수 없다.
     * <p>
     * 전부 끄면 {@link #decide} 가 항상 빈 값을 내 <b>결정 경로가 통째로 사라진다.</b>
     * 규칙을 바꾸려면 새 규칙을 켜 두고 기존 것을 끄는 순서여야 한다.
     */
    private void requireNotLastActive(RepairMethodRule rule) {
        if (rule.isActive() && repository.countByActiveTrue() <= 1) {
            throw new AdminOperationException(AdminErrorCode.LAST_ACTIVE_RULE,
                    "마지막 활성 규칙은 비활성화할 수 없습니다. 대체 규칙을 먼저 등록해 주세요.");
        }
    }

    /**
     * 엔티티의 마지막 불변식이 걸리면 400 으로 바꾼다.
     *
     * <p><b>왜 필요한가.</b> {@code RepairMethodRule.scaled} 는
     * {@code RoundingMode.UNNECESSARY} 를 쓴다 — 소수 셋째 자리가 오면 조용히 반올림하는
     * 대신 {@link ArithmeticException} 을 던진다. 그것을 잡지 않으면 공통 catch-all 이
     * <b>500</b> 을 낸다. 심각도 임계값에서 "반올림" 은 관리자가 입력한 경계와 실제 판정
     * 경계가 달라진다는 뜻이라 반올림도, 500 도 답이 아니다.
     *
     * <p>HTTP 경로에서는 {@code @Digits} 가 먼저 걸러 여기까지 오지 않는다. 이 그물은
     * bean validation 을 거치지 않는 호출자(테스트·배치)를 위한 것이다.
     */
    private RepairMethodRule rule(java.util.function.Supplier<RepairMethodRule> factory) {
        try {
            return factory.get();
        } catch (ArithmeticException | IllegalArgumentException e) {
            throw new AdminOperationException(AdminErrorCode.INVALID_RULE_VALUE,
                    "심각도 값이 저장 가능한 정밀도를 벗어났습니다. 소수점 둘째 자리까지, 9999.99 이하로 입력해 주세요.");
        }
    }

    /** {@link #rule}과 같은 이유. 수정 경로는 반환값이 없다. */
    private void run(Runnable change) {
        try {
            change.run();
        } catch (ArithmeticException | IllegalArgumentException e) {
            throw new AdminOperationException(AdminErrorCode.INVALID_RULE_VALUE,
                    "심각도 값이 저장 가능한 정밀도를 벗어났습니다. 소수점 둘째 자리까지, 9999.99 이하로 입력해 주세요.");
        }
    }

    private void requireCurrentVersion(long current, Long requested) {
        if (requested == null || current != requested) {
            throw new AdminOperationException(AdminErrorCode.VERSION_CONFLICT,
                    "다른 관리자가 먼저 변경했습니다. 다시 조회한 뒤 시도해 주세요. (현재 version %d)"
                            .formatted(current));
        }
    }
}
