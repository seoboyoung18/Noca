package com.ssafy.a307.accident.config;

import com.ssafy.a307.accident.entity.AccidentImageAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ImageQualityPropertiesTest} 와 같은 방식이다 — {@link ApplicationContextRunner} 로
 * 전체 컨텍스트를 띄우지 않고 바인딩만 본다. 보는 것은 세 가지다.
 * <ol>
 *   <li>배포되는 {@code application.properties} 의 값이 그대로 바인딩된다</li>
 *   <li>main 과 test 의 값이 어긋나지 않는다 — 어긋나면 테스트 환경과 운영 동작이 달라진다</li>
 *   <li>잘못된 값이면 요청 시점이 아니라 <b>기동 시점</b>에 실패한다</li>
 * </ol>
 * 잠정값(1600·320·20) 자체를 여기 박아 두지 않은 것은 의도적이다. 확정되면 바뀔 값이고,
 * 그때 테스트까지 고쳐야 하는 것은 잡음이다.
 */
@DisplayName("사고 이미지 업로드 설정")
class AccidentImagePropertiesTest {

    private static final String PREFIX = "app.accident-image.";
    private static final List<String> KEYS = List.of(
            PREFIX + "max-count-per-accident",
            PREFIX + "max-file-size-bytes",
            PREFIX + "resized-max-edge-px",
            PREFIX + "thumbnail-max-edge-px",
            PREFIX + "presigned-url-minutes",
            PREFIX + "download-url-minutes");

    @EnableConfigurationProperties(AccidentImageProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("배포되는 application.properties 의 여섯 값이 그대로 바인딩된다")
    void shippedDefaultsBind() throws IOException {
        Properties shipped = load(resolve("src/main/resources/application.properties"));

        KEYS.forEach(key -> assertThat(shipped.getProperty(key)).as(key).isNotNull());

        runner.withPropertyValues(propertyValues(shipped)).run(context -> {
            AccidentImageProperties properties = context.getBean(AccidentImageProperties.class);
            assertThat(properties.maxCountPerAccident())
                    .isEqualTo(Integer.parseInt(shipped.getProperty(PREFIX + "max-count-per-accident")));
            assertThat(properties.maxFileSizeBytes())
                    .isEqualTo(Integer.parseInt(shipped.getProperty(PREFIX + "max-file-size-bytes")));
            assertThat(properties.resizedMaxEdgePx())
                    .isEqualTo(Integer.parseInt(shipped.getProperty(PREFIX + "resized-max-edge-px")));
            assertThat(properties.thumbnailMaxEdgePx())
                    .isEqualTo(Integer.parseInt(shipped.getProperty(PREFIX + "thumbnail-max-edge-px")));
            assertThat(properties.presignedUrlMinutes())
                    .isEqualTo(Integer.parseInt(shipped.getProperty(PREFIX + "presigned-url-minutes")));
            assertThat(properties.downloadUrlMinutes())
                    .isEqualTo(Integer.parseInt(shipped.getProperty(PREFIX + "download-url-minutes")));
        });
    }

    @Test
    @DisplayName("main 과 test 의 값이 같다")
    void mainAndTestAgree() throws IOException {
        Properties main = load(resolve("src/main/resources/application.properties"));
        Properties test = load(resolve("src/test/resources/application.properties"));

        KEYS.forEach(key -> assertThat(test.getProperty(key))
                .as("%s — test 쪽 application.properties 가 main 과 어긋난다", key)
                .isEqualTo(main.getProperty(key)));
    }

    @Test
    @DisplayName("배포 기본값은 정본 DDL 의 ck_aia_size 를 넘지 않는다")
    void shippedSizeFitsCanonicalConstraint() throws IOException {
        Properties shipped = load(resolve("src/main/resources/application.properties"));

        assertThat(Integer.parseInt(shipped.getProperty(PREFIX + "max-file-size-bytes")))
                .isLessThanOrEqualTo(AccidentImageAsset.MAX_FILE_SIZE_BYTES);
    }

    @Test
    @DisplayName("presigned 유효시간은 프로퍼티 값과 같은 Duration 이 된다")
    void presignedValidity() {
        runner.withPropertyValues(valid()).run(context ->
                assertThat(context.getBean(AccidentImageProperties.class).presignedUrlValidity())
                        .isEqualTo(Duration.ofMinutes(10)));
    }

    @Test
    @DisplayName("조회 URL 유효시간은 업로드용과 별개로 조정된다")
    void downloadValidityIsIndependent() {
        runner.withPropertyValues(override(PREFIX + "download-url-minutes", "3")).run(context -> {
            AccidentImageProperties properties = context.getBean(AccidentImageProperties.class);
            assertThat(properties.downloadUrlValidity()).isEqualTo(Duration.ofMinutes(3));
            assertThat(properties.presignedUrlValidity())
                    .as("업로드용은 그대로여야 한다 — 두 값이 붙어 있으면 하나만 줄일 수 없다")
                    .isEqualTo(Duration.ofMinutes(10));
        });
    }

    @Test
    @DisplayName("조회 URL 유효시간이 하루를 넘으면 기동이 실패한다")
    void downloadTooLongFailsStartup() {
        runner.withPropertyValues(override(PREFIX + "download-url-minutes", "1441"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("조회 URL 유효시간이 빠지면 기동이 실패한다 — 코드에 기본값을 두지 않았다")
    void missingDownloadMinutesFailsStartup() {
        List<String> values = new java.util.ArrayList<>(List.of(valid()));
        values.removeIf(entry -> entry.startsWith(PREFIX + "download-url-minutes="));
        runner.withPropertyValues(values.toArray(String[]::new))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("프로퍼티가 빠지면 기동이 실패한다 — 코드에 기본값을 두지 않았다")
    void missingPropertyFailsStartup() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("장수 상한이 0이면 기동이 실패한다")
    void zeroCountFailsStartup() {
        runner.withPropertyValues(override(PREFIX + "max-count-per-accident", "0"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("장당 상한이 ck_aia_size 를 넘으면 기동이 실패한다")
    void oversizedLimitFailsStartup() {
        runner.withPropertyValues(override(
                        PREFIX + "max-file-size-bytes",
                        String.valueOf(AccidentImageAsset.MAX_FILE_SIZE_BYTES + 1)))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("썸네일이 리사이즈본보다 크거나 같으면 기동이 실패한다")
    void thumbnailNotSmallerFailsStartup() {
        runner.withPropertyValues(override(PREFIX + "thumbnail-max-edge-px", "1600"))
                .run(context -> assertThat(context).hasFailed());
        runner.withPropertyValues(override(PREFIX + "thumbnail-max-edge-px", "1601"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("presigned 유효시간이 하루를 넘으면 기동이 실패한다")
    void presignedTooLongFailsStartup() {
        runner.withPropertyValues(override(PREFIX + "presigned-url-minutes", "1441"))
                .run(context -> assertThat(context).hasFailed());
    }

    private static String[] valid() {
        return new String[]{
                PREFIX + "max-count-per-accident=20",
                PREFIX + "max-file-size-bytes=20971520",
                PREFIX + "resized-max-edge-px=1600",
                PREFIX + "thumbnail-max-edge-px=320",
                PREFIX + "presigned-url-minutes=10",
                PREFIX + "download-url-minutes=10"};
    }

    private static String[] override(String key, String value) {
        List<String> values = new ArrayList<>(List.of(valid()));
        values.replaceAll(entry -> entry.startsWith(key + "=") ? key + "=" + value : entry);
        return values.toArray(String[]::new);
    }

    private static String[] propertyValues(Properties properties) {
        return KEYS.stream()
                .filter(key -> properties.getProperty(key) != null)
                .map(key -> key + "=" + properties.getProperty(key))
                .toArray(String[]::new);
    }

    private static Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static Path resolve(String relativePath) {
        Path path = Path.of(relativePath);
        return Files.exists(path) ? path : Path.of("backend").resolve(relativePath);
    }
}
