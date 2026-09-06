package com.ssafy.a307.estimatevalidation.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class WorkTypeMapperTest {

    @ParameterizedTest
    @CsvSource({
            "교환, REPLACEMENT, EXCHANGE",
            "판금, SHEET_METAL, SHEET_METAL",
            "도장, PAINTING, COATING",
            "수리, REPAIR, REPAIR"
    })
    void mapsEstablishedKoreanTypes(String raw, WorkType expected, StandardRepairMethod method) {
        WorkType mapped = WorkTypeMapper.from(raw).orElseThrow();
        assertThat(mapped).isEqualTo(expected);
        assertThat(mapped.standardMethod()).contains(method);
    }

    @ParameterizedTest
    @CsvSource({"탈착, DETACHMENT", "오버홀, OVERHAUL"})
    void doesNotInventCanonicalRepairMethod(String raw, WorkType expected) {
        WorkType mapped = WorkTypeMapper.from(raw).orElseThrow();
        assertThat(mapped).isEqualTo(expected);
        assertThat(mapped.standardMethod()).isEmpty();
    }
}
