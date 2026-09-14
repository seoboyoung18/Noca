package com.ssafy.a307.estimate;

import com.ssafy.a307.estimate.domain.FallbackStage;
import com.ssafy.a307.estimate.domain.LowConfidenceRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 견적 항목 신뢰도 판정 기준 (S15P21A307-291 · S15P21A307-205).
 *
 * <p>판정은 순수 계산이라 컨텍스트 없이 본다. 설정이 실제로 주입되는지와 본·테스트 설정이
 * 갈라지지 않는지는 {@link Defaults} 가 따로 본다.
 */
@DisplayName("낮은 신뢰도 판정 (S15P21A307-290)")
class LowConfidenceRuleTest {

    /** 운영 기본값과 같은 규칙. */
    private final LowConfidenceRule rule = new LowConfidenceRule(5, FallbackStage.ALL);

    @Nested
    @DisplayName("사례 수")
    class RefCases {

        @Test
        @DisplayName("임계값 미만이면 낮은 신뢰도다")
        void tooFewCasesIsLow() {
            assertThat(rule.isLowConfidence(4, FallbackStage.MODEL)).isTrue();
            assertThat(rule.isLowConfidence(0, FallbackStage.MODEL)).isTrue();
        }

        @Test
        @DisplayName("임계값과 같으면 낮지 않다 — '미만' 이 기준이다")
        void exactlyThresholdIsNotLow() {
            assertThat(rule.isLowConfidence(5, FallbackStage.MODEL)).isFalse();
        }

        @Test
        @DisplayName("충분하면 낮지 않다")
        void enoughCasesIsNotLow() {
            assertThat(rule.isLowConfidence(18, FallbackStage.MODEL)).isFalse();
        }

        @Test
        @DisplayName("사례 수가 없으면 0 으로 본다 — 모르는 것을 '충분하다' 로 읽지 않는다")
        void nullCountIsTreatedAsZero() {
            assertThat(rule.isLowConfidence(null, FallbackStage.MODEL)).isTrue();
        }
    }

    @Nested
    @DisplayName("완화 단계")
    class Fallback {

        @Test
        @DisplayName("기준 단계 이상 넓혔으면 낮은 신뢰도다")
        void relaxedEnoughIsLow() {
            assertThat(rule.isLowConfidence(100, FallbackStage.ALL)).isTrue();
        }

        @Test
        @DisplayName("기준에 못 미치면 낮지 않다 — 지금 CAR_CLASS 는 경고하지 않는다")
        void notRelaxedEnoughIsNotLow() {
            assertThat(rule.isLowConfidence(100, FallbackStage.MODEL)).isFalse();
            assertThat(rule.isLowConfidence(100, FallbackStage.CAR_CLASS)).isFalse();
        }

        @Test
        @DisplayName("기준을 CAR_CLASS 로 내리면 한 단계 완화도 경고한다 — 설정으로 바뀐다")
        void thresholdIsConfigurable() {
            LowConfidenceRule stricter = new LowConfidenceRule(5, FallbackStage.CAR_CLASS);

            assertThat(stricter.isLowConfidence(100, FallbackStage.CAR_CLASS)).isTrue();
            assertThat(stricter.isLowConfidence(100, FallbackStage.MODEL)).isFalse();
        }

        @Test
        @DisplayName("단계를 모르면 완화 조건은 보지 않는다 — 모르는 것을 단정하지 않는다")
        void unknownStageIsNotJudged() {
            assertThat(rule.isLowConfidence(100, null)).isFalse();
            // 사례 수 조건은 그대로 본다
            assertThat(rule.isLowConfidence(1, null)).isTrue();
        }
    }

    @Test
    @DisplayName("둘 중 하나만 걸려도 낮은 신뢰도다 — 근거가 약해지는 경로가 둘이다")
    void eitherConditionIsEnough() {
        assertThat(rule.isLowConfidence(1, FallbackStage.MODEL)).isTrue();      // 사례만
        assertThat(rule.isLowConfidence(100, FallbackStage.ALL)).isTrue();      // 완화만
        assertThat(rule.isLowConfidence(1, FallbackStage.ALL)).isTrue();        // 둘 다
        assertThat(rule.isLowConfidence(100, FallbackStage.MODEL)).isFalse();   // 둘 다 아님
    }

    @Test
    @DisplayName("FallbackStage 선언 순서가 판정의 근거다 — 순서가 바뀌면 이 테스트가 깨진다")
    void stageOrderIsTheContract() {
        assertThat(FallbackStage.values())
                .as("MODEL(안 넓힘) → CAR_CLASS(한 단계) → ALL(전부) 순이어야 ordinal 비교가 성립한다")
                .containsExactly(FallbackStage.MODEL, FallbackStage.CAR_CLASS, FallbackStage.ALL);
    }

    @Nested
    @SpringBootTest
    @DisplayName("설정 주입")
    class Defaults {

        @Autowired
        private LowConfidenceRule injected;

        @Test
        @DisplayName("프로퍼티가 실제로 주입된다")
        void propertiesAreBound() {
            assertThat(injected.minRefCases()).isEqualTo(5);
            assertThat(injected.warnFromStage()).isEqualTo(FallbackStage.ALL);
        }

        @Test
        @DisplayName("본 설정과 테스트 설정이 같은 값이다 — 갈라지면 테스트가 운영을 대변하지 못한다")
        void mainAndTestConfigAgree() {
            String main = read(resource("src/main/resources/application.properties"));

            assertThat(main)
                    .contains("app.estimate-confidence.min-ref-cases=" + injected.minRefCases())
                    .contains("app.estimate-confidence.warn-from-stage=" + injected.warnFromStage());
        }

        private Path resource(String relativePath) {
            Path fromModule = Path.of(relativePath);
            return Files.exists(fromModule) ? fromModule : Path.of("backend").resolve(fromModule);
        }

        private String read(Path path) {
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (java.io.IOException e) {
                throw new UncheckedIOException("파일을 읽지 못했다: " + path.toAbsolutePath(), e);
            }
        }
    }
}
