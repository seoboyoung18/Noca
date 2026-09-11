package com.ssafy.a307.estimate.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 근거 문구 템플릿(S15P21A307-284).
 *
 * <p>이 문구는 사용자가 견적을 믿을지 판단하는 근거다. 그래서 <b>확인되지 않은 것을 적지
 * 않는지</b>가 문장이 예쁜지보다 중요하다.
 */
@DisplayName("근거 문구")
class BasisNarrativeTest {

    @Test
    @DisplayName("명세서 51행 예시 형태로 조립된다")
    void assemblesFullSentence() {
        RefCondition condition = new RefCondition(
                FallbackStage.MODEL,
                new RefCondition.CostDistribution(74_000, 92_000, 118_000),
                2023, 2025, null, null);

        String narrative = BasisNarrative.of(condition, "프론트 범퍼", "교환", 37);

        assertThat(narrative).isEqualTo("2023~2025년 동일 모델 프론트 범퍼 교환 사례 37건의 중앙값 기준");
    }

    /** "동일 모델 37건"과 "전체 37건"은 사용자가 견적을 믿을 근거가 전혀 다르다. */
    @Test
    @DisplayName("조건을 넓힌 단계가 문장에 드러난다")
    void scopeIsVisible() {
        assertThat(narrativeWith(FallbackStage.MODEL)).contains("동일 모델");
        assertThat(narrativeWith(FallbackStage.CAR_CLASS)).contains("동일 차급");
        assertThat(narrativeWith(FallbackStage.ALL)).contains("전체");
    }

    @Test
    @DisplayName("참조 연도가 한 해면 범위로 쓰지 않는다")
    void singleYearIsNotARange() {
        RefCondition condition = new RefCondition(FallbackStage.MODEL, null, 2024, 2024, null, null);

        assertThat(BasisNarrative.of(condition, "본넷", "판금", 8)).startsWith("2024년 ");
    }

    @Nested
    @DisplayName("근거가 모자랄 때")
    class Incomplete {

        /**
         * 산정 로직(S15P21A307-256·257)이 들어오기 전까지 모든 행이 빈 근거다.
         * 그래도 건수는 별도 컬럼에 있어 문장이 만들어진다.
         */
        @Test
        @DisplayName("근거가 비어도 건수만으로 문장을 만든다")
        void emptyBasisStillDescribesCount() {
            String narrative = BasisNarrative.of(RefCondition.EMPTY, "프론트 범퍼", "교환", 12);

            assertThat(narrative).isEqualTo("프론트 범퍼 교환 사례 12건의 기준");
        }

        /** 없는 연도를 그럴듯한 값으로 채우면 사용자는 확인되지 않은 사실을 근거로 읽는다. */
        @Test
        @DisplayName("연도가 한쪽만 있으면 아예 적지 않는다")
        void halfOpenYearRangeIsOmitted() {
            RefCondition condition = new RefCondition(FallbackStage.ALL, null, 2023, null, null, null);

            assertThat(BasisNarrative.of(condition, "본넷", "판금", 5)).doesNotContain("2023");
        }

        /** 중앙값을 못 구했는데 "중앙값 기준"이라고 쓰면 문구가 사실과 달라진다. */
        @Test
        @DisplayName("중앙값이 없으면 중앙값이라고 쓰지 않는다")
        void withoutMedianDoesNotClaimMedian() {
            RefCondition condition = new RefCondition(FallbackStage.MODEL,
                    new RefCondition.CostDistribution(74_000, null, 118_000), 2023, 2025, null, null);

            assertThat(BasisNarrative.of(condition, "본넷", "판금", 5))
                    .endsWith("기준")
                    .doesNotContain("중앙값");
        }

        /** 근거가 없는데 문장을 만들면 "0건의 기준" 같은 말이 된다. */
        @Test
        @DisplayName("참조한 사례가 없으면 문구를 만들지 않는다")
        void noCaseMeansNoNarrative() {
            assertThat(BasisNarrative.of(RefCondition.EMPTY, "본넷", "판금", 0)).isNull();
            assertThat(BasisNarrative.of(RefCondition.EMPTY, "본넷", "판금", null)).isNull();
        }
    }

    /** 명세서 예시의 "(수도권)" 은 폐기다 — repair_case 에 지역 컬럼이 없다. */
    @Test
    @DisplayName("지역은 문장에 들어가지 않는다")
    void regionIsNeverMentioned() {
        RefCondition condition = new RefCondition(
                FallbackStage.MODEL,
                new RefCondition.CostDistribution(74_000, 92_000, 118_000),
                2023, 2025,
                new RefCondition.RepairMethodReason(List.of("exchange"), null),
                List.of(121381L));

        assertThat(BasisNarrative.of(condition, "프론트 범퍼", "교환", 37))
                .doesNotContain("수도권")
                .doesNotContain("지역");
    }

    private String narrativeWith(FallbackStage stage) {
        return BasisNarrative.of(new RefCondition(stage, null, 2023, 2025, null, null),
                "프론트 범퍼", "교환", 37);
    }
}
