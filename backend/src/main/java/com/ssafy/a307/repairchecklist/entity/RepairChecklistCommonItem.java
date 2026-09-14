package com.ssafy.a307.repairchecklist.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 공통 확인 항목 마스터 (S15P21A307-462 · -463). <b>읽기만 한다.</b>
 *
 * <p>문안 6종은 마이그레이션이 넣었고 관리자만 고친다. <b>코드가 문안을 갖지 않는다</b> —
 * 자바에 복사해 두면 DB 와 갈라져 어느 쪽이 화면에 나가는지 알 수 없게 된다. 그래서 이 엔티티에
 * 상수 문자열이 하나도 없다.
 *
 * <p>{@code is_active} 로 끈 항목은 새로 만드는 체크리스트에 들어가지 않지만, <b>이미 만들어진
 * 체크리스트에는 그대로 남는다</b> — 항목의 {@code content} 가 이 {@code message} 의 복사본이기
 * 때문이다({@code -509} 설계 판단, {@code repair_checklist_item.common_code} 주석).
 */
@Entity
@Table(name = "repair_checklist_common_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepairChecklistCommonItem {

    @Id
    @Column(name = "code", length = 30)
    private String code;

    @Column(name = "message", nullable = false, length = 500)
    private String message;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
