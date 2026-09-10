package com.ssafy.a307.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code AccidentImagePropertiesTest} 와 같은 방식이다 — {@link ApplicationContextRunner} 로
 * 전체 컨텍스트를 띄우지 않고 바인딩만 본다.
 *
 * <p>이 설정이 잘못되면 <b>배포 순간 전 화면이 막히는데 서버 로그에는 아무것도 남지 않는다.</b>
 * 그래서 잘못된 값을 요청 시점이 아니라 기동 시점에 잡는 것을 여기서 고정한다.
 */
@DisplayName("CORS 허용 오리진 설정")
class CorsPropertiesTest {

    private static final String KEY = "app.cors.allowed-origins";

    @EnableConfigurationProperties(CorsProperties.class)
    static class TestConfig {
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(TestConfig.class);
    }

    @Nested
    @DisplayName("바인딩")
    class Binding {

        @Test
        @DisplayName("쉼표로 구분한 여러 오리진을 목록으로 받는다")
        void bindsMultipleOrigins() {
            runner().withPropertyValues(KEY + "=https://a307.example.com,http://localhost:5173")
                    .run(ctx -> assertThat(ctx.getBean(CorsProperties.class).allowedOrigins())
                            .containsExactly("https://a307.example.com", "http://localhost:5173"));
        }

        @Test
        @DisplayName("하나만 줘도 목록이 된다")
        void bindsSingleOrigin() {
            runner().withPropertyValues(KEY + "=http://localhost:5173")
                    .run(ctx -> assertThat(ctx.getBean(CorsProperties.class).allowedOrigins())
                            .containsExactly("http://localhost:5173"));
        }

        @Test
        @DisplayName("포트가 없는 배포 도메인도 받는다")
        void bindsOriginWithoutPort() {
            runner().withPropertyValues(KEY + "=https://a307.example.com")
                    .run(ctx -> assertThat(ctx.getBean(CorsProperties.class).allowedOrigins())
                            .containsExactly("https://a307.example.com"));
        }
    }

    @Nested
    @DisplayName("기동 시점에 막는 값")
    class StartupFailure {

        @Test
        @DisplayName("프로퍼티가 없으면 기동이 실패한다 — 빈 목록으로 조용히 돌지 않는다")
        void failsWhenMissing() {
            runner().run(ctx -> assertThat(ctx).hasFailed());
        }

        @Test
        @DisplayName("빈 값이면 기동이 실패한다 — 빈 목록은 모든 오리진 차단이라 하드코딩과 증상이 같다")
        void failsWhenEmpty() {
            runner().withPropertyValues(KEY + "=").run(ctx -> assertThat(ctx).hasFailed());
        }

        @Test
        @DisplayName("\"*\" 는 기동에서 막는다 — allowCredentials 와 함께 쓰면 첫 요청에서 500 이 난다")
        void failsOnWildcard() {
            runner().withPropertyValues(KEY + "=*").run(ctx -> assertThat(ctx).hasFailed());
        }

        @Test
        @DisplayName("와일드카드가 섞인 오리진도 막는다")
        void failsOnPartialWildcard() {
            runner().withPropertyValues(KEY + "=https://*.example.com")
                    .run(ctx -> assertThat(ctx).hasFailed());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "http://localhost:5173/",              // 끝 슬래시
                "https://a307.example.com/app",        // 경로
                "https://a307.example.com?a=1",        // 질의 문자열
                "localhost:5173",                      // scheme 없음
                "https://",                            // host 없음
                "https://user@a307.example.com"        // userInfo
        })
        @DisplayName("scheme://host[:port] 가 아니면 기동에서 막는다 — 두면 오류 없이 전부 차단된다")
        void failsOnNonOriginForm(String origin) {
            runner().withPropertyValues(KEY + "=" + origin)
                    .run(ctx -> assertThat(ctx).hasFailed());
        }

        @Test
        @DisplayName("여러 개 중 하나만 잘못돼도 막는다")
        void failsWhenAnyOriginIsWrong() {
            runner().withPropertyValues(KEY + "=http://localhost:5173,https://a307.example.com/")
                    .run(ctx -> assertThat(ctx).hasFailed());
        }
    }

    @Nested
    @DisplayName("배포 설정 파일")
    class DeployedProperties {

        @Test
        @DisplayName("main 의 기본값은 app.frontend-base-url 을 따른다 — 오리진이 하나면 한 군데만 관리한다")
        void mainDefaultsToFrontendBaseUrl() throws IOException {
            Properties main = load("src/main/resources/application.properties");
            assertThat(main.getProperty(KEY))
                    .as("배포 기본값이 프론트 기준 URL 을 따라야 한 군데만 고치면 된다")
                    .isEqualTo("${CORS_ALLOWED_ORIGINS:${app.frontend-base-url}}");
        }

        @Test
        @DisplayName("test 에도 같은 키가 있다 — 없으면 모든 통합 테스트가 기동에서 죽는다")
        void testPropertiesHasSameKey() throws IOException {
            Properties test = load("src/test/resources/application.properties");
            assertThat(test.getProperty(KEY)).isNotBlank();
        }

        @Test
        @DisplayName("SecurityConfig 에 오리진이 하드코딩되어 있지 않다")
        void securityConfigHasNoHardcodedOrigin() throws IOException {
            String source = Files.readString(
                    Path.of("src/main/java/com/ssafy/a307/config/SecurityConfig.java"),
                    StandardCharsets.UTF_8);
            assertThat(source)
                    .as("setAllowedOrigins 는 프로퍼티에서 값을 받아야 한다")
                    .contains("c.setAllowedOrigins(corsProperties.allowedOrigins())")
                    .doesNotContain("setAllowedOrigins(List.of(\"http");
        }
    }

    private static Properties load(String relativePath) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(relativePath), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
