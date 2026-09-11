package com.ssafy.a307.admin.service;

import com.ssafy.a307.admin.AdminErrorCode;
import com.ssafy.a307.admin.AdminOperationException;
import com.ssafy.a307.admin.dto.AdminPageRequest;
import com.ssafy.a307.admin.dto.AdminPageResponse;
import com.ssafy.a307.admin.dto.EstimateValidationRuleResponse;
import com.ssafy.a307.admin.dto.EstimateValidationRuleUpdateRequest;
import com.ssafy.a307.audit.entity.AuditTargetType;
import com.ssafy.a307.audit.service.AuditLogService;
import com.ssafy.a307.common.security.CurrentMemberProvider;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidationRule;
import com.ssafy.a307.estimatevalidation.repository.EstimateValidationRuleRepository;
import com.ssafy.a307.estimatevalidation.service.EstimateValidationRuleProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 견적서 이상 탐지 임계값 관리.
 *
 * <h2>수정이 아니라 새 버전이다</h2>
 * {@code PATCH} 지만 기존 행을 고치지 않고 다음 버전을 만든다. 그래야 과거 검증이 어떤
 * 기준으로 판정됐는지 되짚을 수 있다 — {@code estimate_validation.rule_version} 이 그 버전을 가리킨다.
 *
 * <h2>바뀐 것이 없으면 버전을 만들지 않는다</h2>
 * 판정에 쓰이는 네 값이 모두 같으면 <b>새 버전도 감사 행도 만들지 않고</b> 현재 규칙을
 * 그대로 돌려준다. 버튼 연타나 재시도로 버전 번호가 늘면
 * {@code estimate_validation.rule_version} 이 가리키는 "기준이 바뀐 지점" 이 의미를 잃는다.
 * {@code changeNote} 만 다른 경우도 변경으로 보지 않는다.
 *
 * <h2>즉시 반영</h2>
 * 새 버전이 커밋되는 순간 {@code EstimateValidationRuleProvider} 가 다음 검증부터 그 값을 읽는다.
 * 캐시가 없어 무효화도 전파도 필요 없고, 다중 인스턴스에서도 같다.
 * <b>이미 끝난 검증은 다시 계산되지 않는다</b> — 결과가 자기 행에 저장돼 있다.
 */
@Service
@RequiredArgsConstructor
public class AdminEstimateValidationRuleService {

    private static final Set<String> SORTABLE = Set.of("ruleVersion", "createdAt");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("ruleVersion"));

    private final EstimateValidationRuleRepository repository;
    private final EstimateValidationRuleProvider provider;
    private final CurrentMemberProvider currentMemberProvider;
    private final AuditLogService auditLog;

    @Transactional(readOnly = true)
    public EstimateValidationRuleResponse current() {
        return EstimateValidationRuleResponse.from(provider.currentRule());
    }

    @Transactional(readOnly = true)
    public AdminPageResponse<EstimateValidationRuleResponse> history(
            Integer page, Integer size, String sort) {

        return AdminPageResponse.of(
                repository.findAllByOrderByRuleVersionDesc(
                        AdminPageRequest.of(page, size, sort, DEFAULT_SORT, SORTABLE)),
                EstimateValidationRuleResponse::from);
    }

    /**
     * 다음 버전을 만들어 즉시 현재 규칙으로 만든다.
     *
     * <p><b>{@code baseVersion} 이 현재 버전과 다르면 409 다.</b> 그 사이 다른 관리자가 새
     * 버전을 만들었다는 뜻이고, 그대로 저장하면 상대의 변경이 한 세대 만에 덮인다.
     * PK 유니크 위반도 같은 상황이라 함께 409 로 접는다 — 두 요청이 동시에 같은 다음 번호를
     * 노렸을 때다.
     */
    @Transactional
    public EstimateValidationRuleResponse createNextVersion(EstimateValidationRuleUpdateRequest request) {
        EstimateValidationRule current = provider.currentRule();
        if (!current.getRuleVersion().equals(request.baseVersion())) {
            throw new AdminOperationException(AdminErrorCode.VERSION_CONFLICT,
                    "다른 관리자가 먼저 변경했습니다. 다시 조회한 뒤 시도해 주세요. (현재 version %d)"
                            .formatted(current.getRuleVersion()));
        }

        if (unchanged(current, request)) {
            // 판정에 쓰이는 네 값이 모두 그대로다. 새 버전을 만들면 아무것도 바뀌지 않은
            // 지점에 번호가 생기고, estimate_validation.rule_version 이 가리키는
            // "기준이 바뀐 시점" 이 의미를 잃는다. 이력도 남기지 않는다 — 변경이 없었으니
            // "누가 무엇을 바꿨다" 고 적을 것이 없다.
            return EstimateValidationRuleResponse.from(current);
        }

        EstimateValidationRule next;
        try {
            next = EstimateValidationRule.nextVersion(
                    current.getRuleVersion() + 1,
                    EstimateValidationRule.FIXED_REFERENCE_PERCENTILE,
                    request.severeOverP75Multiplier(),
                    request.cautionTotalDifferenceRatio(),
                    request.needsReviewTotalDifferenceRatio(),
                    request.needsReviewItemCount().shortValue(),
                    currentMemberProvider.currentMemberId(),
                    request.changeNote());
        } catch (IllegalArgumentException e) {
            // 엔티티의 마지막 불변식이 걸렸다. 잘못된 값은 저장되지 않고 이력도 남지 않는다.
            throw new AdminOperationException(AdminErrorCode.INVALID_RULE_VALUE, e.getMessage());
        }

        EstimateValidationRule saved;
        try {
            saved = repository.saveAndFlush(next);
        } catch (DataIntegrityViolationException e) {
            throw new AdminOperationException(AdminErrorCode.VERSION_CONFLICT,
                    "동시에 다른 변경이 저장됐습니다. 다시 조회한 뒤 시도해 주세요.");
        }

        EstimateValidationRuleResponse before = EstimateValidationRuleResponse.from(current);
        EstimateValidationRuleResponse after = EstimateValidationRuleResponse.from(saved);
        auditLog.updated(AuditTargetType.ESTIMATE_VALIDATION_RULE,
                String.valueOf(saved.getRuleVersion()), before, after);
        return after;
    }

    /**
     * 판정에 실제로 쓰이는 네 값이 모두 같은가.
     *
     * <p><b>{@code changeNote} 는 비교하지 않는다.</b> 사유만 다시 쓰는 것은 판정을 바꾸지
     * 않으므로 새 버전이 될 이유가 없다. 사유를 정정하고 싶다면 임계값 변경과 함께 남긴다.
     *
     * <p>{@code compareTo} 로 비교한다 — {@code BigDecimal.equals} 는 scale 까지 보므로
     * {@code 0.10} 과 {@code 0.1000} 을 다른 값으로 판단해 "바뀐 것 없음" 을 놓친다.
     */
    private boolean unchanged(EstimateValidationRule current,
                              EstimateValidationRuleUpdateRequest request) {
        return current.severeOverP75Multiplier()
                        .compareTo(request.severeOverP75Multiplier()) == 0
                && current.cautionTotalDifferenceRatio()
                        .compareTo(request.cautionTotalDifferenceRatio()) == 0
                && current.needsReviewTotalDifferenceRatio()
                        .compareTo(request.needsReviewTotalDifferenceRatio()) == 0
                && current.needsReviewItemCount() == request.needsReviewItemCount();
    }
}
