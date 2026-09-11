package com.ssafy.a307.admin.service;

import com.ssafy.a307.admin.dto.EstimateValidationRuleResponse;
import com.ssafy.a307.admin.dto.RepairMethodRuleResponse;
import com.ssafy.a307.admin.dto.RuleFieldBoundsResponse;
import com.ssafy.a307.admin.dto.RuleOverviewResponse;
import com.ssafy.a307.estimatevalidation.entity.RepairCode;
import com.ssafy.a307.estimatevalidation.entity.RepairCodeType;
import com.ssafy.a307.estimatevalidation.repository.RepairCodeRepository;
import com.ssafy.a307.estimatevalidation.repository.RepairMethodRuleRepository;
import com.ssafy.a307.estimatevalidation.service.EstimateValidationRuleProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 규칙 관리 화면의 <b>읽기 합본</b>.
 *
 * <p>기존 서비스들을 조합만 한다 — 검증·저장 로직을 여기에 복제하지 않는다.
 * 쓰기는 각 리소스의 원래 서비스가 계속 담당한다.
 *
 * <h2>한 트랜잭션에서 읽는다</h2>
 * 수리 방식 규칙과 이상 탐지 임계값을 따로 부르면 그 사이 다른 관리자의 저장이 끼어들어
 * 화면이 서로 다른 시점의 값을 한 폼에 담을 수 있다. 읽기 트랜잭션 하나로 묶어 그것을 막는다.
 *
 * <h2>페이지네이션하지 않는다</h2>
 * 수리 방식 규칙은 손상 유형 4종 × 심각도 구간 몇 개 규모다. 목록 화면이 아니라 <b>규칙
 * 전체를 한눈에 보고 구간의 구멍·겹침을 확인하는</b> 화면이라 페이지로 자르면 오히려 쓸 수
 * 없다. 페이지가 필요한 조회는 {@code GET /api/admin/repair-method-rules} 가 따로 있다.
 */
@Service
@RequiredArgsConstructor
public class AdminRuleOverviewService {

    /** 화면이 구간의 구멍을 눈으로 찾을 수 있게, 적용 범위별로 심각도 오름차순이다. */
    private static final Sort RULE_ORDER = Sort.by(
            Sort.Order.asc("damageType"),
            Sort.Order.asc("partCode"),
            Sort.Order.asc("severityMin"),
            Sort.Order.desc("priority"));

    private final RepairMethodRuleRepository ruleRepository;
    private final RepairCodeRepository codeRepository;
    private final EstimateValidationRuleProvider ruleProvider;

    @Transactional(readOnly = true)
    public RuleOverviewResponse overview() {
        List<RepairMethodRuleResponse> rules = ruleRepository.findAll(RULE_ORDER).stream()
                .map(RepairMethodRuleResponse::from)
                .toList();

        return new RuleOverviewResponse(
                rules,
                EstimateValidationRuleResponse.from(ruleProvider.currentRule()),
                RuleFieldBoundsResponse.of(
                        activeCodes(RepairCodeType.DAMAGE_TYPE),
                        activeCodes(RepairCodeType.REPAIR_METHOD)));
    }

    /**
     * 등록에 실제로 쓸 수 있는 코드만 준다.
     * <p>
     * 비활성 코드를 드롭다운에 넣으면 관리자가 고른 뒤에야 400 {@code INACTIVE_CODE} 를
     * 만난다 — 고를 수 없는 것을 보여주지 않는 편이 낫다.
     */
    private List<String> activeCodes(RepairCodeType codeType) {
        return codeRepository.findByCodeTypeOrderByDisplayOrderAscCodeAsc(codeType).stream()
                .filter(RepairCode::isActive)
                .map(RepairCode::getCode)
                .toList();
    }
}
