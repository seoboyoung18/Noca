package com.ssafy.a307.estimatevalidation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Objects;

/**
 * 수리 방식·손상 유형 canonical code 의 <b>표시층</b>.
 *
 * <p><b>이 테이블로 새 코드를 만들 수 없다.</b> 네 가지 수리 방식과 네 가지 손상 유형은
 * {@code StandardRepairMethod} enum, {@code damaged_part}·{@code estimate_item} 의 DDL CHECK,
 * {@code repair_cost_stat} 조회 조건이 <b>같은 문자열을 공유</b>한다. 관리자가 DB 행만 추가하면
 * 행은 늘어나는데 계산은 그 값을 모른다 — 행 하나가 조용히 죽은 코드가 된다.
 *
 * <p>그래서 관리자가 바꿀 수 있는 것은 <b>표시명 · 표시 순서 · 활성 상태</b> 뿐이다.
 * 새 canonical code 가 필요하면 enum·CHECK·계산 로직을 함께 바꾸는 별도 작업이다.
 *
 * <p><b>소비 경로</b> — {@code RepairMethodRuleService} 가 규칙을 등록·수정할 때 손상 유형과
 * 수리 방식이 <b>여기 있고 활성인지</b> 확인한다. 비활성 코드로는 새 규칙을 만들 수 없다.
 * 표시층만 만들고 아무도 읽지 않는 테이블이 되지 않게 하는 지점이다.
 */
@Entity
@Table(name = "repair_code")
@IdClass(RepairCode.Key.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepairCode {

    public static final int MAX_DISPLAY_NAME_LENGTH = 50;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "code_type", nullable = false, length = 20, updatable = false)
    private RepairCodeType codeType;

    @Id
    @Column(name = "code", nullable = false, length = 20, updatable = false)
    private String code;

    @Column(name = "display_name", nullable = false, length = MAX_DISPLAY_NAME_LENGTH)
    private String displayName;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** 표시명과 순서만 바꾼다. {@code codeType}·{@code code} 는 {@code updatable = false} 다. */
    public void modify(String displayName, short displayOrder) {
        String stripped = displayName == null ? null : displayName.strip();
        if (stripped == null || stripped.isEmpty()) {
            throw new IllegalArgumentException("displayName is required");
        }
        this.displayName = stripped;
        this.displayOrder = displayOrder;
    }

    public void changeStatus(boolean active) {
        this.active = active;
    }

    /** 복합 키. {@code (code_type, code)} 다. */
    public static class Key implements Serializable {

        private RepairCodeType codeType;
        private String code;

        public Key() {
        }

        public Key(RepairCodeType codeType, String code) {
            this.codeType = codeType;
            this.code = code;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key other)) return false;
            return codeType == other.codeType && Objects.equals(code, other.code);
        }

        @Override
        public int hashCode() {
            return Objects.hash(codeType, code);
        }
    }
}
