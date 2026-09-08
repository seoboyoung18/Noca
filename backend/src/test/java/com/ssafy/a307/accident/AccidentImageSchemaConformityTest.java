package com.ssafy.a307.accident;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 테스트 스키마가 정본 DDL 과 어긋나지 않는지 본다.
 * <p>
 * H2 스키마는 손으로 옮긴 사본이라 정본이 바뀌면 조용히 낡는다. 그러면 테스트는 통과하는데 운영은
 * 깨진다 — <b>테스트를 통과시키기 위해 H2 제약을 정본보다 약하게 만드는</b> 실수가 가장 위험하다.
 * 그래서 두 파일의 컬럼·NULL·DEFAULT·CHECK·UNIQUE·FK 를 문자열로 대조한다.
 * <p>
 * JPA ↔ H2 일치는 이 테스트가 보지 않는다. {@code spring.jpa.hibernate.ddl-auto=validate} 가
 * 컨텍스트 기동 시점에 검증하므로, 엔티티 매핑이 어긋나면 {@code @SpringBootTest} 가 통째로 뜨지 않는다.
 * 셋 중 둘을 여기서, 나머지 하나를 기동으로 확인하는 구조다.
 */
@DisplayName("사고 이미지 스키마 정본 대조")
class AccidentImageSchemaConformityTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"accident_image", "accident_image_asset"})
    @DisplayName("정본 DDL 과 H2 스키마의 정의가 같다")
    void tablesMatchCanonical(String table) throws IOException {
        List<String> canonical = definitions(read(canonicalDdl()), table);
        List<String> h2 = definitions(read(h2Schema()), table);

        assertThat(canonical).as("정본 DDL 에서 %s 를 찾지 못했다", table).isNotEmpty();
        assertThat(h2).as("%s 의 정의가 정본과 다르다", table).containsExactlyElementsOf(canonical);
    }

    @Test
    @DisplayName("정본의 ix_img_accident 인덱스가 H2 에도 있다")
    void indexExists() throws IOException {
        assertThat(normalize(read(h2Schema())))
                .contains("CREATE INDEX IX_IMG_ACCIDENT ON ACCIDENT_IMAGE (ACCIDENT_ID)");
    }

    @Test
    @DisplayName("H2 에서 variant CHECK 목록에 OVERLAY 가 없다 — uk_aia 와 충돌하는 값이다")
    void overlayIsNotAllowed() throws IOException {
        List<String> definitions = definitions(read(h2Schema()), "accident_image_asset");

        assertThat(definitions)
                .anySatisfy(definition -> assertThat(definition)
                        .contains("CK_AIA_VAR")
                        .contains("'ORIGINAL','RESIZED','THUMBNAIL','BLURRED'"));
        assertThat(definitions).noneMatch(definition -> definition.contains("OVERLAY"));
    }

    @Test
    @DisplayName("구버전 22테이블 DDL 의 컬럼이 되살아나지 않았다")
    void legacyColumnsAreNotRestored() throws IOException {
        List<String> image = definitions(read(h2Schema()), "accident_image");

        assertThat(image).noneMatch(definition -> definition.startsWith("ANGLE_TAG"));
        assertThat(image).noneMatch(definition -> definition.contains("S3_KEY_ORIGINAL"));
        assertThat(image).noneMatch(definition -> definition.contains("S3_KEY_RESIZED"));
        assertThat(image).noneMatch(definition -> definition.contains("S3_KEY_THUMBNAIL"));
        assertThat(image).noneMatch(definition -> definition.contains("S3_KEY_BLURRED"));
    }

    @Test
    @DisplayName("각도 태그는 학습 사례 이미지에만 있다 — 사고 이미지에는 없는 것이 정본이다")
    void angleTagBelongsToRepairCaseImage() throws IOException {
        String canonical = normalize(read(canonicalDdl()));

        assertThat(definitions(read(canonicalDdl()), "accident_image"))
                .noneMatch(definition -> definition.startsWith("ANGLE_TAG"));
        assertThat(definitions(read(canonicalDdl()), "repair_case_image"))
                .anyMatch(definition -> definition.startsWith("ANGLE_TAG"));
        assertThat(canonical).contains("ANGLE_TAG");
    }

    /**
     * {@code CREATE TABLE <table> ( ... );} 안의 정의를 정규화해 하나씩 돌려준다.
     * H2 와 PostgreSQL 의 표기 차이(BIGSERIAL·TIMESTAMPTZ)만 맞춘다 — 그 외의 차이는 실제 차이다.
     */
    private static List<String> definitions(String sql, String table) {
        String normalized = normalize(sql);
        String marker = "CREATE TABLE " + table.toUpperCase(Locale.ROOT) + " (";
        int start = normalized.indexOf(marker);
        if (start < 0) return List.of();
        start += marker.length();

        int depth = 1;
        int end = start;
        while (end < normalized.length() && depth > 0) {
            char c = normalized.charAt(end);
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (depth == 0) break;
            end++;
        }

        List<String> definitions = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int nesting = 0;
        for (char c : normalized.substring(start, end).toCharArray()) {
            if (c == '(') nesting++;
            if (c == ')') nesting--;
            if (c == ',' && nesting == 0) {
                add(definitions, current);
                continue;
            }
            current.append(c);
        }
        add(definitions, current);
        return definitions;
    }

    private static void add(List<String> definitions, StringBuilder current) {
        String value = current.toString().strip();
        if (!value.isEmpty()) definitions.add(value);
        current.setLength(0);
    }

    /** 주석 제거 · 공백 정리 · 대문자화 · H2 표기 차이 흡수. */
    private static String normalize(String sql) {
        String withoutComments = sql.replaceAll("--[^\\n]*", " ");
        return withoutComments
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT)
                .replace("BIGSERIAL", "BIGINT GENERATED BY DEFAULT AS IDENTITY")
                .replace("TIMESTAMPTZ", "TIMESTAMP WITH TIME ZONE")
                .replaceAll("\\s*\\(\\s*", " (")
                .replaceAll("\\s*\\)\\s*", ") ")
                .replaceAll("\\s+", " ")
                .replaceAll("\\s*,\\s*", ",");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Path canonicalDdl() {
        return repoFile("Docs/Erd/A307_ddl_final.sql");
    }

    private static Path h2Schema() {
        Path fromModule = Path.of("src/test/resources/schema-h2.sql");
        return Files.exists(fromModule) ? fromModule : Path.of("backend").resolve(fromModule);
    }

    /** 테스트 작업 디렉터리가 {@code backend} 일 때와 저장소 루트일 때를 모두 지원한다. */
    private static Path repoFile(String relativePath) {
        Path fromModule = Path.of("..").resolve(relativePath);
        return Files.exists(fromModule) ? fromModule : Path.of(relativePath);
    }
}
