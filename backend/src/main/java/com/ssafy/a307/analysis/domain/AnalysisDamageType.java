package com.ssafy.a307.analysis.domain;

import java.util.Locale;

/**
 * 손상 유형 — 파이프라인 표기(UPPER_SNAKE)와 서비스 DDL 표기를 잇는 <b>유일한 변환 지점</b>.
 *
 * <p>이 프로젝트에는 같은 손상을 가리키는 표기가 세 벌 있다.
 *
 * <pre>
 *   모델        scratched     (소문자)
 *   파이프라인   SCRATCHED     (UPPER_SNAKE)  ← 백엔드가 받는 것
 *   서비스 DDL   'Scratched'   (PascalCase)   ← ck_dp_damage
 * </pre>
 *
 * <p><b>셋 중 하나가 틀린 것이 아니다.</b> 백엔드가 받는 것은 표준화 계약
 * ({@code shared/vision/common_schema.json}, {@code damage.code}) 의 UPPER_SNAKE
 * 하나뿐이고, DB 에 넣을 때만 DDL 표기로 바꾼다. <b>변환을 여기 한 곳에만 두는 이유</b>는
 * 두 곳에 두면 한쪽만 고쳐졌을 때 {@code ck_dp_damage} 위반이 INSERT 시점에야 드러나고,
 * 그때는 어느 쪽이 옳은지 알 수 없기 때문이다.
 *
 * <p><b>DDL 값은 임의로 정한 것이 아니다.</b> {@code shared/vision/catalog.py} 의
 * {@code DAMAGES} 가 각 코드에 {@code name_en} 을 달아 두었고, DDL 의
 * {@code CHECK (damage_type IN ('Scratched','Separated','Crushed','Breakage'))} 가
 * <b>그 {@code name_en} 과 글자까지 같다.</b> 그래서 이 enum 은 새 규칙을 만드는 것이 아니라
 * 이미 있는 대응을 자바 쪽에 옮겨 적은 것이다.
 */
public enum AnalysisDamageType {

    SCRATCHED("Scratched"),
    SEPARATED("Separated"),
    CRUSHED("Crushed"),
    BREAKAGE("Breakage");

    /** {@code damaged_part.damage_type} 에 그대로 들어가는 값. {@code ck_dp_damage} 가 이것만 받는다. */
    private final String columnValue;

    AnalysisDamageType(String columnValue) {
        this.columnValue = columnValue;
    }

    public String columnValue() {
        return columnValue;
    }

    /**
     * 계약의 {@code damage.code}(UPPER_SNAKE)를 해석한다.
     *
     * <p><b>모르는 코드를 조용히 넘기지 않는다.</b> 계약 스키마가 이미 네 값으로 열거를 막고 있어
     * 여기까지 다른 값이 오면 계약과 구현이 어긋났다는 뜻이고, 그때 임의의 기본값을 넣으면
     * 근거 없는 손상 유형이 견적까지 흘러간다.
     */
    public static AnalysisDamageType from(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("damage.code 는 필수입니다.");
        }
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        for (AnalysisDamageType value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }
        throw new IllegalArgumentException("알 수 없는 손상 유형입니다: " + code);
    }
}
