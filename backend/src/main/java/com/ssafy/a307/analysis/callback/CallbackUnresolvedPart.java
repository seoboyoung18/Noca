package com.ssafy.a307.analysis.callback;

/**
 * 산정하지 못한 부위 하나. 계약의 {@code unresolvedParts[]} 원소 (S15P21A307-534).
 *
 * <p>여러 부위 중 일부만 사례가 모자라 금액을 못 낸 경우 AI 는 {@code estimable: true} 를
 * 유지하고 {@code totals} 를 산정한 항목만으로 합산한다. 이 목록은 <b>그 합산에서 빠진 부위</b>다.
 *
 * <p><b>Bean Validation 을 걸지 않는다.</b> 이 값은 금액이 아니라 안내용이다. 원소 하나가
 * 이상하다고 400 을 내면 AI 가 재시도해도 같은 본문이라 영영 저장되지 않고, 사용자는 산정된
 * 금액까지 잃는다. 이상한 원소는 저장하는 쪽({@link AnalysisResultPersister})이 골라 버린다.
 *
 * @param partCode   표준 부품 코드
 * @param damageType 손상 유형 (UPPER_SNAKE 또는 DDL 표기)
 * @param reason     산정하지 못한 사유 코드. 지금은 {@code INSUFFICIENT_CASES} 하나다
 */
public record CallbackUnresolvedPart(String partCode, String damageType, String reason) {
}
