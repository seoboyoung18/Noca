package com.ssafy.a307.estimate.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 산정 근거 저장 구조(S15P21A307-285).
 *
 * <p>채우는 쪽(산정 로직 S15P21A307-256·257)이 아직 없어 실제 데이터로는 검증할 수 없다.
 * 그래도 <b>계약은 지금 고정할 수 있다</b> — 쓴 것이 그대로 읽히는지, 비어 있거나 깨진 근거가
 * 조회를 무너뜨리지 않는지가 이 구조가 지켜야 할 전부다.
 */
@DisplayName("산정 근거 저장 구조")
class RefConditionReaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RefConditionReader reader = new RefConditionReader(objectMapper);

    @Test
    @DisplayName("쓴 근거가 그대로 읽힌다")
    void roundTrips() {
        RefCondition written = new RefCondition(
                FallbackStage.PRICE_TIER,
                new RefCondition.CostDistribution(74_000, 92_000, 118_000),
                2023, 2025,
                new RefCondition.RepairMethodReason(List.of("sheet_metal", "exchange"), "MAJORITY"),
                List.of(121381L, 121414L));

        RefCondition read = reader.read(objectMapper.writeValueAsString(written));

        assertThat(read).isEqualTo(written);
        assertThat(read.isEmpty()).isFalse();
    }

    /** 합친 손상 유형(S15P21A307-566)도 왕복하고, 그 필드가 없던 예전 스냅샷은 빈 목록으로 읽힌다. */
    @Test
    @DisplayName("합친 손상 유형이 왕복하고 예전 스냅샷은 빈 목록이다")
    void mergedDamageTypesRoundTrip() {
        RefCondition written = new RefCondition(FallbackStage.MODEL, null, null, null, null,
                List.of(121381L), List.of("Scratched", "Breakage"));

        assertThat(reader.read(objectMapper.writeValueAsString(written)).mergedDamageTypes())
                .containsExactly("Scratched", "Breakage");
        assertThat(reader.read("{\"fallbackStage\":\"MODEL\"}").mergedDamageTypes()).isEmpty();
    }

    /**
     * 조건을 어디까지 넓혔는지가 근거의 핵심이다. "동일 차종 37건"과 "전체 37건"은
     * 사용자가 견적을 믿을지 판단하는 근거가 전혀 다르다.
     */
    @Test
    @DisplayName("폴백 단계 세 값이 모두 왕복한다")
    void allFallbackStagesSurvive() {
        for (FallbackStage stage : FallbackStage.values()) {
            RefCondition written = new RefCondition(stage, null, null, null, null, null);

            assertThat(reader.read(objectMapper.writeValueAsString(written)).fallbackStage())
                    .isEqualTo(stage);
        }
    }

    @Nested
    @DisplayName("근거가 없을 때")
    class Absent {

        /**
         * 산정 로직이 들어오기 전까지 모든 행이 이 상태다. 컬럼이 NOT NULL 이라 빈 객체로
         * 저장된다 — 오류가 아니라 "근거 없음"이라고 화면에 알려야 할 상태다(명세서 51행).
         */
        @Test
        @DisplayName("빈 객체는 빈 근거다 — 예외가 아니다")
        void emptyObjectIsEmptyBasis() {
            RefCondition read = reader.read("{}");

            assertThat(read.isEmpty()).isTrue();
            assertThat(read.fallbackStage()).isNull();
            assertThat(read.costDistribution()).isNull();
        }

        @Test
        @DisplayName("null 과 공백도 빈 근거다")
        void nullAndBlankAreEmptyBasis() {
            assertThat(reader.read(null).isEmpty()).isTrue();
            assertThat(reader.read("   ").isEmpty()).isTrue();
        }

        /**
         * 근거 한 줄이 깨졌다고 견적 조회를 500 으로 끊으면 사용자는 금액도 항목도 못 본다.
         */
        @Test
        @DisplayName("깨진 JSON 은 조회를 끊지 않고 빈 근거가 된다")
        void malformedDoesNotBreakQuery() {
            assertThatCode(() -> reader.read("{\"fallbackStage\":")).doesNotThrowAnyException();

            assertThat(reader.read("{\"fallbackStage\":").isEmpty()).isTrue();
            assertThat(reader.read("모르는 값").isEmpty()).isTrue();
        }

        /** 조건 완화 단계는 알지만 통계를 못 구한 경우처럼, 일부만 있는 근거도 정상이다. */
        @Test
        @DisplayName("일부만 채워진 근거도 읽힌다")
        void partialBasisIsRead() {
            RefCondition read = reader.read("{\"fallbackStage\":\"ALL\",\"refYearFrom\":2023}");

            assertThat(read.isEmpty()).isFalse();
            assertThat(read.fallbackStage()).isEqualTo(FallbackStage.ALL);
            assertThat(read.refYearFrom()).isEqualTo(2023);
            assertThat(read.refYearTo()).isNull();
            assertThat(read.costDistribution()).isNull();
        }
    }

    /**
     * 산정 로직이 나중에 필드를 더한다. 그때 먼저 저장된 행을 읽지 못하면 과거 견적의 근거가
     * 통째로 사라진다 — 견적에 버전을 쌓아 이력을 보존한 것이 무의미해진다.
     */
    @Test
    @DisplayName("모르는 필드가 섞여도 아는 값은 읽는다")
    void unknownFieldsAreIgnored() {
        String withFutureField = """
                {"fallbackStage":"MODEL","refYearFrom":2024,"laborRateSource":"REGION_AVG"}
                """;

        RefCondition read = reader.read(withFutureField);

        assertThat(read.fallbackStage()).isEqualTo(FallbackStage.MODEL);
        assertThat(read.refYearFrom()).isEqualTo(2024);
    }

    /** 후보를 함께 남기지 않으면 "둘 중 골랐다"와 "하나뿐이었다"를 나중에 구분할 수 없다. */
    @Test
    @DisplayName("수리 방식 후보 목록이 보존된다")
    void repairMethodCandidatesSurvive() {
        RefCondition written = new RefCondition(null, null, null, null,
                new RefCondition.RepairMethodReason(List.of("coating"), null), null);

        RefCondition read = reader.read(objectMapper.writeValueAsString(written));

        assertThat(read.repairMethodReason().candidates()).containsExactly("coating");
        assertThat(read.repairMethodReason().reasonCode()).isNull();
    }
}
