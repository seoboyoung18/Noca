package com.ssafy.a307.admin.dto;

import java.util.List;

/**
 * 규칙 관리 화면이 처음 그릴 때 필요한 것 전부.
 *
 * <h2>왜 묶어서 주는가</h2>
 * 이 화면 하나가 수리 방식 규칙 목록, 이상 탐지 임계값, 그리고 입력 폼의 경계값을 동시에
 * 필요로 한다. 세 번 나눠 부르면 <b>그 사이에 다른 관리자가 규칙을 바꿀 수 있고</b>,
 * 화면은 서로 다른 시점의 값을 한 폼에 담게 된다. 한 번의 읽기 트랜잭션에서 셋을 함께 읽으면
 * 그 어긋남이 없다.
 *
 * <p><b>수정은 여기로 하지 않는다.</b> 이 응답은 읽기 전용 합본이고, 저장은 각 리소스의
 * 원래 엔드포인트를 쓴다 — 쓰기 경로가 둘이 되면 검증과 이력이 갈라진다.
 *
 * @param repairMethodRules  <b>활성·비활성을 모두</b> 담는다. 관리자는 "왜 이 규칙이 안 먹는지"
 *                           를 알아야 하고, 그 답이 비활성인 경우가 많다.
 *                           비어 있으면 빈 배열이다 — 규칙이 없는 것은 오류가 아니다
 * @param estimateAnomalyRule 지금 적용 중인 이상 탐지 임계값. 수정할 때 이
 *                            {@code ruleVersion} 을 {@code baseVersion} 으로 보낸다
 * @param bounds             입력 폼이 쓸 단위·경계·선택 가능한 코드 목록
 */
public record RuleOverviewResponse(
        List<RepairMethodRuleResponse> repairMethodRules,
        EstimateValidationRuleResponse estimateAnomalyRule,
        RuleFieldBoundsResponse bounds) {
}
