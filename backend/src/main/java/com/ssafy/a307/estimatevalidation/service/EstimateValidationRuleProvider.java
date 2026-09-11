package com.ssafy.a307.estimatevalidation.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidationRule;
import com.ssafy.a307.estimatevalidation.repository.EstimateValidationRuleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판정 규칙 공급 경계. 검증 로직이 설정 클래스를 직접 들고 있던 결합을 여기로 끊는다.
 *
 * <h2>캐시를 두지 않는다</h2>
 * 검증이 시작될 때 DB 에서 한 번 읽는다. 이유는 <b>다중 인스턴스에서 캐시 전파 문제가 없기</b>
 * 때문이다 — 관리자가 규칙을 바꾸면 다음 검증부터 어느 인스턴스에서든 새 값이 적용된다.
 * 캐시를 넣으면 무효화 전파가 필요하고, 그 복잡도를 정당화할 성능 실측이 아직 없다.
 * 검증 한 건에 한 번 도는 단건 PK 조회다.
 *
 * <h2>한 검증 안에서는 같은 스냅샷을 쓴다</h2>
 * 호출자가 {@link #currentRule()} 을 한 번 부르고 그 객체를 끝까지 넘긴다. 판정 중간에 다시
 * 읽으면 같은 검증이 두 규칙으로 계산될 수 있다.
 */
@Service
@RequiredArgsConstructor
public class EstimateValidationRuleProvider {

    private final EstimateValidationRuleRepository repository;

    /**
     * 현재 규칙 스냅샷.
     *
     * <p><b>없으면 조용히 기본값으로 넘어가지 않는다.</b> 규칙 행이 없다는 것은 시드가 적용되지
     * 않았다는 뜻이고, 그 상태로 임의의 임계값을 써서 판정하면 사용자에게 근거 없는 등급이 나간다.
     * 503 으로 끊고 무엇을 적용해야 하는지 로그·메시지로 알린다.
     */
    @Transactional(readOnly = true)
    public EstimateValidationRule currentRule() {
        return repository.findFirstByOrderByRuleVersionDesc()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.SERVICE_UNAVAILABLE,
                        "견적서 판정 규칙이 설정되지 않았습니다. Docs/Erd/A307_admin_code_seed.sql 을 적용해 주세요."));
    }
}
