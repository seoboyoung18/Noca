package com.ssafy.a307.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 임계값을 코드 밖으로 뺀 것이 실제로 동작하는지 검증한다. 세 가지를 본다.
 * <ol>
 *   <li>배포되는 {@code application.properties} 의 값이 그대로 바인딩된다</li>
 *   <li>환경변수가 그 값을 덮는다 — <b>어떤 이름이 통하는지</b>까지 확인한다</li>
 *   <li>잘못된 값이면 요청 시점이 아니라 <b>기동 시점</b>에 실패한다</li>
 * </ol>
 *
 * <p>{@code @SpringBootTest} 대신 {@link ApplicationContextRunner} 를 쓴다.
 * 전체 컨텍스트(H2·Redis·Security)를 띄우지 않아 빠르고, 기존 테스트 환경과 무관하다.
 */
@DisplayName("이미지 품질 임계값 설정")
class ImageQualityPropertiesTest {

    private static final String PREFIX = "app.image-quality.";
    private static final String ENABLED = PREFIX + "enabled";
    private static final String MIN_SHORT_EDGE = PREFIX + "min-short-edge-px";
    private static final String BLUR_THRESHOLD = PREFIX + "blur-variance-threshold";

    /** 오버라이드 테스트의 바닥값. 여기 값이 덮이는지를 본다. */
    private static final Map<String, Object> BASE = Map.of(
            ENABLED, "false",
            MIN_SHORT_EDGE, "720",
            BLUR_THRESHOLD, "100.0");

    @EnableConfigurationProperties(ImageQualityProperties.class)
    static class TestConfig {
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

    // ------------------------------------------------------------------ 1

    @Test
    @DisplayName("배포되는 application.properties 의 값이 그대로 바인딩된다")
    void shippedDefaultsBind() throws IOException {
        Properties shipped = load(resolve("src/main/resources/application.properties"));

        // 세 항목이 실제로 파일에 있는가. 코드에 기본값이 없으므로 빠지면 운영이 뜨지 않는다.
        assertThat(shipped.getProperty(ENABLED)).as(ENABLED).isNotNull();
        assertThat(shipped.getProperty(MIN_SHORT_EDGE)).as(MIN_SHORT_EDGE).isNotNull();
        assertThat(shipped.getProperty(BLUR_THRESHOLD)).as(BLUR_THRESHOLD).isNotNull();

        // 파일에 적힌 값과 바인딩 결과가 같은가.
        // 720/100.0 을 여기 박아 두지 않은 것은 의도적이다 — 실험으로 확정되면 바뀔 잠정값이고,
        // 그때 테스트까지 같이 고쳐야 하는 것은 잡음이다. 여기서 볼 것은 "파일 값이 그대로 온다" 뿐이다.
        runner.withPropertyValues(propertyValues(shipped)).run(context -> {
            assertThat(context).hasNotFailed();
            ImageQualityProperties bound = context.getBean(ImageQualityProperties.class);
            assertThat(bound.enabled())
                    .isEqualTo(Boolean.parseBoolean(shipped.getProperty(ENABLED)));
            assertThat(bound.minShortEdgePx())
                    .isEqualTo(Integer.parseInt(shipped.getProperty(MIN_SHORT_EDGE).trim()));
            assertThat(bound.blurVarianceThreshold())
                    .isEqualTo(Double.parseDouble(shipped.getProperty(BLUR_THRESHOLD).trim()));
        });

        // 테스트 클래스패스의 application.properties 가 main 쪽을 가리므로 값이 두 곳에 있다.
        // 어긋나면 "테스트는 통과하는데 운영은 다른 값으로 돈다" 가 되므로 여기서 잡는다.
        Properties forTests = load(resolve("src/test/resources/application.properties"));
        assertThat(forTests.getProperty(ENABLED)).isEqualTo(shipped.getProperty(ENABLED));
        assertThat(forTests.getProperty(MIN_SHORT_EDGE)).isEqualTo(shipped.getProperty(MIN_SHORT_EDGE));
        assertThat(forTests.getProperty(BLUR_THRESHOLD)).isEqualTo(shipped.getProperty(BLUR_THRESHOLD));
    }

    // ------------------------------------------------------------------ 2

    @Test
    @DisplayName("환경변수가 기본값을 덮는다 — 통하는 이름 두 가지와, 통하지 않는 이름")
    void environmentVariablesOverrideDefaults() {
        // (가) 정식 형태: 대문자화 · '.' 를 '_' 로 · '-' 는 제거
        withEnv(Map.of(
                "APP_IMAGEQUALITY_ENABLED", "true",
                "APP_IMAGEQUALITY_MINSHORTEDGEPX", "1080",
                "APP_IMAGEQUALITY_BLURVARIANCETHRESHOLD", "55.5"
        )).run(context -> {
            assertThat(context).hasNotFailed();
            ImageQualityProperties bound = context.getBean(ImageQualityProperties.class);
            assertThat(bound.enabled()).isTrue();
            assertThat(bound.minShortEdgePx()).isEqualTo(1080);
            assertThat(bound.blurVarianceThreshold()).isEqualTo(55.5);
        });

        // (나) '-' 를 '_' 로 바꾼 형태도 통한다. 운영에서는 읽기 쉬운 이쪽을 권한다.
        withEnv(Map.of(
                "APP_IMAGE_QUALITY_ENABLED", "true",
                "APP_IMAGE_QUALITY_MIN_SHORT_EDGE_PX", "1440",
                "APP_IMAGE_QUALITY_BLUR_VARIANCE_THRESHOLD", "42.0"
        )).run(context -> {
            assertThat(context).hasNotFailed();
            ImageQualityProperties bound = context.getBean(ImageQualityProperties.class);
            assertThat(bound.enabled()).isTrue();
            assertThat(bound.minShortEdgePx()).isEqualTo(1440);
            assertThat(bound.blurVarianceThreshold()).isEqualTo(42.0);
        });

        // (다) 두 형태를 섞으면 오류 없이 무시된다. 이 Task 가 막으려는 실패가 바로 이것이라
        //      통하지 않는다는 사실을 테스트로 못 박아 둔다.
        withEnv(Map.of("APP_IMAGEQUALITY_MIN_SHORT_EDGE_PX", "2000")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ImageQualityProperties.class).minShortEdgePx())
                    .as("섞인 이름은 조용히 무시되고 기본값이 남는다")
                    .isEqualTo(720);
        });
    }

    // ------------------------------------------------------------------ 3

    @Test
    @DisplayName("잘못된 값이면 요청 때가 아니라 기동 때 실패한다")
    void invalidValuesFailAtStartup() {
        // 0 이면 어떤 이미지도 걸리지 않아 판정이 조용히 무력화된다.
        assertStartupFails(MIN_SHORT_EDGE, "0");
        assertStartupFails(MIN_SHORT_EDGE, "-1");
        assertStartupFails(BLUR_THRESHOLD, "0");
        assertStartupFails(BLUR_THRESHOLD, "-0.5");

        // 숫자가 아니면 바인딩 자체가 실패한다.
        assertStartupFails(MIN_SHORT_EDGE, "720px");

        // 항목이 통째로 빠져도 실패한다 — 코드에 기본값을 두지 않은 결과다.
        runner.run(context -> assertThat(context).hasFailed());
    }

    private void assertStartupFails(String key, String badValue) {
        Map<String, Object> values = new LinkedHashMap<>(BASE);
        values.put(key, badValue);
        runner.withPropertyValues(propertyValues(values)).run(context -> {
            assertThat(context).as("%s=%s 는 기동을 막아야 한다", key, badValue).hasFailed();
            assertThat(causeChainOf(context.getStartupFailure())).contains(key);
        });
    }

    // ------------------------------------------------------------------ 도구

    /**
     * 실제 환경변수처럼 읽히는 프로퍼티 소스를 얹는다. 소스 이름이 {@code systemEnvironment} 여야
     * Spring Boot 가 환경변수용 이름 변환 규칙을 적용하므로 상수를 그대로 쓴다.
     * 바닥값은 맨 뒤에 깔아 환경변수가 이기는 순서를 만든다.
     */
    private ApplicationContextRunner withEnv(Map<String, Object> environmentVariables) {
        return runner.withInitializer(context -> {
            MutablePropertySources sources = context.getEnvironment().getPropertySources();
            sources.addLast(new MapPropertySource("defaults", BASE));
            sources.addFirst(new SystemEnvironmentPropertySource(
                    StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, environmentVariables));
        });
    }

    /** Gradle 은 프로젝트 디렉터리에서 테스트를 돌리지만, 저장소 루트에서 돌려도 찾도록 한 단계 더 본다. */
    private static Path resolve(String relativePath) {
        Path path = Path.of(relativePath);
        return Files.exists(path) ? path : Path.of("backend").resolve(relativePath);
    }

    /** Spring Boot 의 properties 로더와 같은 인코딩으로 읽는다. 키와 값은 ASCII 다. */
    private static Properties load(Path path) throws IOException {
        assertThat(path).as("설정 파일이 있어야 한다").exists();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.ISO_8859_1)) {
            properties.load(reader);
        }
        return properties;
    }

    private static String[] propertyValues(Properties properties) {
        Map<String, Object> values = new LinkedHashMap<>();
        properties.stringPropertyNames().stream()
                .filter(name -> name.startsWith(PREFIX))
                .forEach(name -> values.put(name, properties.getProperty(name).trim()));
        return propertyValues(values);
    }

    private static String[] propertyValues(Map<String, Object> values) {
        return values.entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    private static String causeChainOf(Throwable throwable) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            text.append(current).append('\n');
        }
        return text.toString();
    }
}
