package com.ssafy.a307.estimate.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code estimate.unresolved_parts} 읽기 (S15P21A307-534). 무엇이 와도 예외 없이 목록을 돌려준다.
 */
@DisplayName("산정하지 못한 부위 읽기")
class UnresolvedPartsReaderTest {

    private final UnresolvedPartsReader reader = new UnresolvedPartsReader(new ObjectMapper());

    @Test
    @DisplayName("배열을 원소 차례 그대로 읽는다")
    void readsArray() {
        assertThat(reader.read("""
                [{"partCode":"HEAD_LAMP_L","damageType":"Crushed","reason":"INSUFFICIENT_CASES"},
                 {"partCode":"HOOD","damageType":null,"reason":null}]
                """))
                .containsExactly(
                        new UnresolvedPart("HEAD_LAMP_L", "Crushed", "INSUFFICIENT_CASES"),
                        new UnresolvedPart("HOOD", null, null));
    }

    @Test
    @DisplayName("이 열 이전 견적(null)·빈 문자열·빈 배열은 빈 목록이다")
    void emptyInputsAreEmptyList() {
        assertThat(reader.read(null)).isEmpty();
        assertThat(reader.read("  ")).isEmpty();
        assertThat(reader.read("[]")).isEmpty();
    }

    @Test
    @DisplayName("깨졌거나 배열이 아니면 예외 대신 빈 목록이다 — 견적 조회가 500 이 되면 안 된다")
    void brokenInputIsEmptyList() {
        assertThat(reader.read("[{\"partCode\":")).isEmpty();
        assertThat(reader.read("{\"partCode\":\"HOOD\"}")).isEmpty();
    }

    @Test
    @DisplayName("모르는 키는 무시하고, 부품 코드가 없는 원소는 뺀다")
    void ignoresUnknownKeysAndDropsCodelessElements() {
        assertThat(reader.read("""
                [null,
                 {"damageType":"Crushed"},
                 {"partCode":" ","reason":"INSUFFICIENT_CASES"},
                 {"partCode":"HOOD","damageType":"Crushed","reason":"INSUFFICIENT_CASES","extra":1}]
                """))
                .containsExactly(new UnresolvedPart("HOOD", "Crushed", "INSUFFICIENT_CASES"));
    }
}
