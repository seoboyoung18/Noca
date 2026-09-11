package com.ssafy.a307.estimatevalidation.repository;

import com.ssafy.a307.estimatevalidation.entity.EstimateValidationRule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 이상 탐지 규칙 버전들. <b>현재 규칙 = {@code ruleVersion} 최대값</b>이고 활성 플래그가 없다.
 */
public interface EstimateValidationRuleRepository extends JpaRepository<EstimateValidationRule, Integer> {

    /** 현재 규칙. 검증 한 건이 시작할 때 한 번 읽어 그 스냅샷을 끝까지 쓴다. */
    Optional<EstimateValidationRule> findFirstByOrderByRuleVersionDesc();

    Page<EstimateValidationRule> findAllByOrderByRuleVersionDesc(Pageable pageable);
}
