package com.ssafy.a307.estimate.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 견적 화면·리포트·PDF 가 함께 쓰는 고지 문구 한 줄 (S15P21A307-288).
 *
 * <p><b>이름이 {@code EstimateNotice} 가 아닌 이유</b> — 그 이름은 응답 DTO
 * ({@link com.ssafy.a307.estimate.dto.EstimateNotice}) 가 이미 쓰고 있다. 둘은 모양이 거의
 * 같지만 바뀌는 이유가 다르다. DTO 는 <b>프론트와의 계약</b>이라 함부로 못 바꾸고, 이쪽은
 * <b>테이블 모양</b>이라 컬럼이 늘 수 있다. 한 클래스로 합치면 DB 에 컬럼을 하나 더하는 일이
 * 곧바로 API 계약 변경이 된다.
 *
 * <p><b>쓰기 경로가 없다.</b> 문구는 운영자가 {@code psql UPDATE} 로 바꾼다 — 그것이
 * "코드 수정 없이 변경 가능하도록" 이라는 요구를 만족하는 가장 단순한 방법이고, 관리자
 * 화면은 이 티켓의 범위가 아니다(관리자 마스터 관리는 S15P21A307-362~365 의 몫이다).
 * 그래서 setter 도 정적 팩토리도 두지 않았다. 필요해지면 그때 만든다.
 *
 * <p><b>{@code updated_at} 을 매핑하지 않는다.</b> 컬럼은 있지만 앱이 읽지도 쓰지도 않는다 —
 * "언제 바꿨나" 를 psql 로 확인하기 위한 운영용 값이다. 매핑하지 않아도
 * {@code ddl-auto=validate} 는 통과한다. 검증 대상은 <b>엔티티가 선언한 컬럼</b>이지
 * 테이블의 모든 컬럼이 아니다.
 */
@Entity
@Table(name = "estimate_notice")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EstimateNoticeContent {

    /**
     * 화면이 문구별로 다른 처리를 해야 할 때 쓰는 식별자. 이 값이 곧 PK 다 —
     * 대리 키를 두면 같은 코드가 두 행 생길 수 있고, 그러면 어느 쪽이 진짜인지 알 수 없다.
     */
    @Id
    @Column(name = "code", nullable = false)
    private String code;

    /** 사용자에게 그대로 보여 줄 문장. 서버가 가공하지 않는다. */
    @Column(name = "message", nullable = false)
    private String message;

    /** 여러 문구를 함께 내릴 때의 순서. 같은 값이면 코드 오름차순으로 갈린다. */
    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    /**
     * 내려보낼지 여부. 문구를 지우지 않고 잠시 내리기 위한 값이다 — 지워 버리면
     * 나중에 같은 문장을 다시 쓸 때 원문이 남지 않는다.
     */
    @Column(name = "is_active", nullable = false)
    private boolean active;
}
