package com.ssafy.a307.estimatevalidation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "part_code")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartCode {
    @Id
    @Column(name = "part_code", length = 50)
    private String partCode;

    @Column(name = "name_ko", nullable = false, length = 50)
    private String nameKo;

    @Column(name = "layout_zone", nullable = false, length = 20)
    private String layoutZone;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    /**
     * AI 핵심 라벨 32종인지 견적 전용 확장 코드인지.
     *
     * <p><b>예전에는 {@code displayOrder} 로만 구분됐다</b>(시드가 1~32 와 101~124 로 나눠 넣었다).
     * 그것은 표시 순서일 뿐이라 관리자가 순서를 바꾸는 순간 구분이 조용히 무너진다.
     * 명시적 속성으로 옮겼다 — {@code Docs/Erd/migrations/2026-09-10-admin-master-and-rules.sql} 이
     * 시드 상태를 일회성으로 이관한다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "code_scope", nullable = false, length = 20)
    private PartCodeScope codeScope;

    /** 낙관적 잠금. {@code VehicleModel.version} 과 같은 이유다. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    private PartCode(String partCode, String nameKo, String layoutZone,
                     short displayOrder, PartCodeScope codeScope) {
        this.partCode = partCode;
        this.nameKo = nameKo;
        this.layoutZone = layoutZone;
        this.displayOrder = displayOrder;
        this.codeScope = codeScope;
        this.active = true;
    }

    /**
     * 관리자 등록.
     *
     * <p><b>{@code partCode} 는 이 순간 이후 바뀌지 않는다.</b> {@code damaged_part} ·
     * {@code repair_cost_stat} · {@code estimate_validation_item} · {@code part_name_mapping} 이
     * 이 문자열을 그대로 들고 있어서, 코드를 바꾸면 과거 분석·견적·통계의 의미가 달라진다.
     * 바꿔야 하면 새 코드를 등록하고 기존 코드를 비활성화한다.
     */
    public static PartCode register(String partCode, String nameKo, String layoutZone,
                                    short displayOrder, PartCodeScope codeScope) {
        return new PartCode(
                requireText(partCode, "partCode"),
                requireText(nameKo, "nameKo"),
                requireText(layoutZone, "layoutZone"),
                displayOrder,
                java.util.Objects.requireNonNull(codeScope, "codeScope"));
    }

    /** 관리자 수정. 코드 자체는 대상이 아니다. */
    public void modify(String nameKo, String layoutZone, short displayOrder, PartCodeScope codeScope) {
        this.nameKo = requireText(nameKo, "nameKo");
        this.layoutZone = requireText(layoutZone, "layoutZone");
        this.displayOrder = displayOrder;
        this.codeScope = java.util.Objects.requireNonNull(codeScope, "codeScope");
    }

    /**
     * 활성 상태 전환. 비활성 부품은 {@code PartNameMappingService.loadDictionary()} 의 사전과
     * 신규 매핑 대상에서 빠지지만, <b>이미 그 코드로 저장된 분석·견적·검증 결과는 그대로 조회된다.</b>
     */
    public void changeStatus(boolean active) {
        this.active = active;
    }

    private static String requireText(String value, String field) {
        String stripped = value == null ? null : value.strip();
        if (stripped == null || stripped.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return stripped;
    }
}
