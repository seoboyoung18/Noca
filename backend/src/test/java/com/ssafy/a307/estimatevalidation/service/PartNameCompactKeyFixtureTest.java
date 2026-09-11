package com.ssafy.a307.estimatevalidation.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java {@link PartNameCompactKey} 가 Python {@code compact()} 와 같은 결과를 내는지.
 *
 * <p><b>Python 테스트와 같은 파일을 읽는다</b>
 * ({@code pipeline/standardization/fixtures/part_name_compact_fixture.tsv}).
 * 한쪽 구현만 고치면 반대쪽 테스트가 깨지므로 두 구현이 조용히 갈라지지 않는다.
 * Python 쪽은 {@code pipeline/standardization/test_part_name_compact.py} 다.
 *
 * <p>이 fixture 는 <b>{@link PartNameNormalizer} 를 검증하지 않는다.</b> 두 키는 역할이
 * 다르고 시드 15,308건 중 79.4% 가 서로 다른 결과를 낸다 — 자세한 내용은
 * {@link PartNameCompactKey} Javadoc 에 있다.
 */
@DisplayName("부품명 비교 키 — Python compact() 동등성")
class PartNameCompactKeyFixtureTest {

    private static final String EMPTY_MARKER = "<EMPTY>";
    private static final Path FIXTURE = fixturePath();

    private final PartNameCompactKey compactKey = new PartNameCompactKey();

    record Case(String source, String expected) {
        @Override
        public String toString() {
            return "%s → %s".formatted(source, expected.isEmpty() ? EMPTY_MARKER : expected);
        }
    }

    static List<Case> fixture() {
        List<Case> cases = new ArrayList<>();
        for (String line : readFixtureLines()) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            String[] columns = line.split("\t", 2);
            assertThat(columns).as("fixture 행은 <입력>\\t<기대값> 두 칸이다: %s", line).hasSize(2);
            cases.add(new Case(columns[0],
                    EMPTY_MARKER.equals(columns[1]) ? "" : columns[1]));
        }
        return cases;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixture")
    @DisplayName("fixture 의 기대값과 같다")
    void matchesPythonFixture(Case expectation) {
        assertThat(compactKey.compact(expectation.source())).isEqualTo(expectation.expected());
    }

    @Test
    @DisplayName("fixture 를 실제로 읽었다 — 경로가 어긋나면 0건이 되어 아무것도 검증하지 않는다")
    void fixtureIsLoaded() {
        assertThat(FIXTURE).as("Python 과 공유하는 fixture 경로").exists();
        assertThat(fixture()).hasSizeGreaterThan(20);
    }

    @Test
    @DisplayName("null·빈 문자열·공백만 있는 입력은 빈 키다")
    void nullAndBlank() {
        assertThat(compactKey.compact(null)).isEmpty();
        assertThat(compactKey.compact("")).isEmpty();
        assertThat(compactKey.compact("   ")).isEmpty();
    }

    @Test
    @DisplayName("Java \\s 가 놓치는 유니코드 공백도 제거한다 — Python \\s 와 집합이 같아야 한다")
    void removesUnicodeWhitespaceOutsideJavaBackslashS() {
        assertThat(compactKey.compact("앞\u00a0범퍼")).isEqualTo("앞범퍼");   // NBSP
        assertThat(compactKey.compact("앞\u2009범퍼")).isEqualTo("앞범퍼");   // thin space
        assertThat(compactKey.compact("앞\u0085범퍼")).isEqualTo("앞범퍼");   // NEL
        assertThat(compactKey.compact("앞\u001f범퍼")).isEqualTo("앞범퍼");   // unit separator
    }

    @Test
    @DisplayName("한 번 접은 키를 다시 접어도 같다")
    void isIdempotent() {
        for (Case expectation : fixture()) {
            assertThat(compactKey.compact(expectation.expected()))
                    .isEqualTo(expectation.expected());
        }
    }

    @Test
    @DisplayName("런타임 조회 키와 결과가 다르다 — 두 키를 혼용하면 안 된다는 사실을 고정한다")
    void differsFromRuntimeNormalizer() {
        PartNameNormalizer runtime = new PartNameNormalizer();

        // 괄호: compact 는 남기고, 런타임은 지운다
        assertThat(compactKey.compact("앞범퍼(좌)")).isEqualTo("앞범퍼(좌)");
        assertThat(runtime.normalize("앞범퍼(좌)")).isEqualTo("앞범퍼좌");
        // 작업 접미사: compact 는 남기고, 런타임은 지운다
        assertThat(compactKey.compact("앞범퍼 교환")).isEqualTo("앞범퍼교환");
        assertThat(runtime.normalize("앞범퍼 교환")).isEqualTo("앞범퍼");
        // 좌우 표기: compact 는 그대로 두고, 런타임은 치환한다
        assertThat(compactKey.compact("왼쪽 도어")).isEqualTo("왼쪽도어");
        assertThat(runtime.normalize("왼쪽 도어")).isEqualTo("좌도어");
        // 구분자: compact 는 지우고, 런타임은 남긴다
        assertThat(compactKey.compact("앞-범퍼")).isEqualTo("앞범퍼");
        assertThat(runtime.normalize("앞-범퍼")).isEqualTo("앞-범퍼");
    }

    private static List<String> readFixtureLines() {
        try {
            return Files.readAllLines(FIXTURE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("fixture 를 읽지 못했다: " + FIXTURE.toAbsolutePath(), e);
        }
    }

    /** 테스트 작업 디렉터리가 {@code backend} 일 때와 저장소 루트일 때를 모두 지원한다. */
    private static Path fixturePath() {
        Path relative = Path.of("pipeline", "standardization", "fixtures",
                "part_name_compact_fixture.tsv");
        Path fromBackendModule = Path.of("..").resolve(relative);
        return Files.exists(fromBackendModule) ? fromBackendModule : relative;
    }
}
