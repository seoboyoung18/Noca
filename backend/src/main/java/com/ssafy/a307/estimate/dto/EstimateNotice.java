package com.ssafy.a307.estimate.dto;

/**
 * 견적 화면에 함께 띄우는 고지 문구.
 *
 * <p><b>지금은 항상 비어 있다.</b> 문구를 담을 테이블이 아직 없다 —
 * S15P21A307-288 이 "코드 수정 없이 변경 가능하도록" 테이블화하는 작업이고, 그게 붙어야
 * 값이 채워진다. 필드를 미리 두는 이유는 프론트가 지금부터 이 모양으로 그려 두면
 * 288 이 들어올 때 <b>계약이 바뀌지 않기</b> 때문이다.
 *
 * <p>문구를 코드에 상수로 박지 않는다. 그러면 288 의 요구사항이 그 자리에서 깨진다.
 *
 * @param code    화면이 문구별로 다른 처리를 해야 할 때 쓰는 식별자 (예: {@code LABOR_ONLY})
 * @param message 사용자에게 보여 줄 문장
 */
public record EstimateNotice(String code, String message) {
}
