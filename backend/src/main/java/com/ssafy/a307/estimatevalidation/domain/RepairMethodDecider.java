package com.ssafy.a307.estimatevalidation.domain;

import com.ssafy.a307.estimatevalidation.entity.RepairMethodRule;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 심각도 점수를 수리 방식으로 바꾸는 결정 경계.
 *
 * <h2>이 클래스가 왜 있는가</h2>
 * 규칙 테이블과 관리 API 만 만들고 "즉시 반영 완료" 라고 하면, 실제로 그 규칙을 읽는 코드가
 * 없으므로 아무것도 반영되지 않는다. 이 클래스가 <b>규칙의 유일한 해석 지점</b>이고,
 * 관리 API 의 겹침 검증과 같은 경계 규칙({@link RepairMethodRule#covers})을 공유한다.
 *
 * <h2>⚠️ 아직 호출하는 분석 파이프라인이 없다</h2>
 * {@code damaged_part} 를 쓰는 파손 검출·견적 산정 코드는 이 저장소에 없다
 * ({@code analysis_job} 계열 엔티티 0개). 그래서 지금 이 결정기를 부르는 운영 경로는 없고,
 * <b>분석 서비스가 붙을 때 연결할 조회 계약</b>으로 둔다. 계약이 맞는지는 규칙 등록 →
 * 결정 → 규칙 변경 → 재결정까지 도는 통합 테스트가 지킨다.
 *
 * <p>상태가 없어 스프링 빈으로 두지 않는다 — {@code GradeDecider} 와 같은 방식이다.
 */
public final class RepairMethodDecider {

    /**
     * 점수를 품는 규칙 중 가장 앞선 것의 수리 방식.
     *
     * @param candidates {@code RepairMethodRuleRepository.findCandidates} 가 이미 우선순위대로
     *                   정렬해 준 활성 규칙. 정렬을 여기서 다시 하지 않는 이유는 DB 인덱스를
     *                   그대로 쓰기 위해서다
     * @param severity   {@code damaged_part.severity_score}. {@code null} 이면 판정하지 않는다 —
     *                   점수를 모르는데 수리 방식을 정하면 근거 없는 견적이 된다
     * @return 맞는 규칙이 없으면 {@link Optional#empty()}. 비어 있는 것을 기본값으로 덮지 않는다
     */
    public Optional<String> decide(List<RepairMethodRule> candidates, BigDecimal severity) {
        if (candidates == null || severity == null) return Optional.empty();
        return candidates.stream()
                .filter(rule -> rule.covers(severity))
                .findFirst()
                .map(RepairMethodRule::getRepairMethod);
    }
}
