package com.ssafy.a307.accident.image;

import com.ssafy.a307.common.storage.ObjectStorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>어댑터는 두 버킷이 모두 설정됐을 때만 뜬다.</b>
 *
 * <p>없으면 소비자가 받는 {@code Optional} 이 비어 기존 503 이 그대로 유지된다 —
 * 설정 없는 로컬·테스트에서 AWS 자격증명을 요구하지 않기 위해서다. <b>반쯤 설정된 채로 뜨면
 * 첫 업로드에서야 실패</b>하므로 그것도 막는다.
 *
 * <p>실제 AWS 를 부르지 않는다. {@code S3Client.builder().region(...).build()} 는 네트워크를
 * 타지 않으며(그래서 리전을 명시한다) 자격증명은 첫 호출까지 해석되지 않는다. 버킷 이름은 더미다.
 */
@DisplayName("사고 이미지 저장소 어댑터 선택")
class AccidentImageStorageSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(Adapter.class)
            .withPropertyValues("app.object-storage.region=ap-northeast-2");

    @Test
    @DisplayName("버킷 설정이 없으면 어댑터가 뜨지 않는다 — 이미지 API 는 503 그대로다")
    void noAdapterWithoutBuckets() {
        runner.run(context -> assertThat(context).doesNotHaveBean(AccidentImageStoragePort.class));
    }

    @Test
    @DisplayName("staging 만 있으면 뜨지 않는다 — 파생본을 둘 곳이 없다")
    void stagingAloneIsNotEnough() {
        runner.withPropertyValues("app.object-storage.staging-bucket=test-staging")
                .run(context -> assertThat(context).doesNotHaveBean(AccidentImageStoragePort.class));
    }

    @Test
    @DisplayName("service 만 있으면 뜨지 않는다 — 브라우저가 올릴 곳이 없다")
    void serviceAloneIsNotEnough() {
        runner.withPropertyValues("app.object-storage.service-bucket=test-service")
                .run(context -> assertThat(context).doesNotHaveBean(AccidentImageStoragePort.class));
    }

    @Test
    @DisplayName("두 버킷이 다 있으면 어댑터 하나가 뜬다")
    void bothBucketsBringTheAdapter() {
        runner.withPropertyValues(
                        "app.object-storage.staging-bucket=test-staging",
                        "app.object-storage.service-bucket=test-service")
                .run(context -> {
                    assertThat(context).hasSingleBean(AccidentImageStoragePort.class);
                    assertThat(context).hasSingleBean(S3AccidentImageStorage.class);
                });
    }

    /**
     * <b>빈 문자열이 "있는 값" 으로 세어지지 않는지</b> 고정한다. 버킷은 {@code ${…:}} 참조라
     * 환경변수가 없으면 빈 문자열로 존재한다 — {@code @ConditionalOnProperty} 를 값 없이 쓰면
     * 그것도 통과해 버킷 없이 빈이 만들어진다.
     */
    @Test
    @DisplayName("버킷이 빈 문자열이면 뜨지 않는다")
    void blankBucketsSelectNothing() {
        runner.withPropertyValues(
                        "app.object-storage.staging-bucket=",
                        "app.object-storage.service-bucket=")
                .run(context -> assertThat(context).doesNotHaveBean(AccidentImageStoragePort.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ObjectStorageProperties.class)
    @Import(S3AccidentImageStorage.class)
    static class Adapter {
    }
}
