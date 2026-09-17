package com.ssafy.a307.estimate.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 산정하지 못해 총액에서 뺀 부위 하나. {@code estimate.unresolved_parts} JSONB 배열 원소의
 * 내용 계약이다 (S15P21A307-534).
 *
 * <p>쓰는 곳은 {@code AnalysisResultPersister}, 읽는 곳은 {@link UnresolvedPartsReader} 다.
 * 한 타입을 같이 써서 두 쪽의 키 이름이 어긋나지 않게 한다 — {@link RefCondition} 과 같은 방식이다.
 *
 * <p><b>부위 이름을 담지 않는다.</b> 이름은 {@code part_code.name_ko} 에 있고, 여기에 복사하면
 * 마스터를 고쳤을 때 과거 견적만 옛 이름으로 남는다. 조회할 때 붙인다.
 *
 * @param partCode   표준 부품 코드. 저장 전에 {@code part_code} 마스터에 있는지 확인했다
 * @param damageType DDL 표기({@code Scratched} 등). 알 수 없었으면 {@code null}
 * @param reason     사유 코드({@code INSUFFICIENT_CASES} 등). 없었으면 {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UnresolvedPart(String partCode, String damageType, String reason) {
}
