package com.ssafy.a307.estimatevalidation.file;

import com.ssafy.a307.common.storage.ObjectStorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>문서 저장소 어댑터는 하나만 뜬다.</b>
 *
 * <p>둘이 함께 뜨면 소비자의 {@code Optional<DocumentStoragePort>} 주입이 빈 두 개를 만나
 * 컨텍스트가 통째로 실패한다. 배타를 주석이 아니라 <b>구조</b>로 보장한 것을 여기서 고정한다 —
 * 둘 다 {@code app.document-storage.provider} 하나를 보고, 값이 각각 {@code local}·{@code s3} 다.
 *
 * <p>실제 AWS 를 부르지 않는다. {@code S3Client.builder().region(...).build()} 는 네트워크를
 * 타지 않으며(그래서 리전을 명시한다), 자격증명은 첫 호출 때까지 해석되지 않는다.
 * 버킷 이름은 더미다.
 */
@DisplayName("문서 저장소 어댑터 선택")
class DocumentStorageAdapterSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(Adapters.class)
            .withPropertyValues("app.object-storage.region=ap-northeast-2");

    @Test
    @DisplayName("설정이 없으면 어느 어댑터도 뜨지 않는다 — 파일 업로드는 503 이다")
    void noAdapterWithoutConfiguration() {
        runner.run(context -> assertThat(context).doesNotHaveBean(DocumentStoragePort.class));
    }

    @Test
    @DisplayName("provider=local 이면 로컬만 뜬다")
    void localOnly(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) {
        runner.withPropertyValues(
                        "app.document-storage.provider=local",
                        "app.document-storage.local.root=" + tempDir.toString().replace("\\", "/"))
                .run(context -> {
                    assertThat(context).hasSingleBean(DocumentStoragePort.class);
                    assertThat(context).hasSingleBean(LocalDocumentStorageAdapter.class);
                    assertThat(context).doesNotHaveBean(S3DocumentStorage.class);
                });
    }

    @Test
    @DisplayName("provider=s3 이고 서비스 버킷이 있으면 S3 만 뜬다")
    void s3Only() {
        runner.withPropertyValues(
                        "app.document-storage.provider=s3",
                        "app.object-storage.service-bucket=test-bucket")
                .run(context -> {
                    assertThat(context).hasSingleBean(DocumentStoragePort.class);
                    assertThat(context).hasSingleBean(S3DocumentStorage.class);
                    assertThat(context).doesNotHaveBean(LocalDocumentStorageAdapter.class);
                });
    }

    @Test
    @DisplayName("provider=s3 인데 버킷이 비면 뜨지 않는다 — 반쯤 설정된 채로 뜨지 않는다")
    void s3NeedsBucket() {
        runner.withPropertyValues(
                        "app.document-storage.provider=s3",
                        "app.object-storage.service-bucket=")
                .run(context -> assertThat(context).doesNotHaveBean(DocumentStoragePort.class));
    }

    /**
     * <b>빈 문자열이 "있는 값" 으로 세어지지 않는지</b> 고정한다. {@code provider} 는
     * {@code ${DOCUMENT_STORAGE_PROVIDER:}} 참조라 환경변수가 없으면 빈 문자열로 존재한다 —
     * {@code @ConditionalOnProperty} 를 값 없이 쓰면 그것도 통과해 버린다
     * ({@code GmsKeyPresentCondition} 이 같은 함정을 기록해 두었다).
     */
    @Test
    @DisplayName("provider 가 빈 문자열이면 어느 쪽도 뜨지 않는다")
    void emptyProviderSelectsNothing() {
        runner.withPropertyValues(
                        "app.document-storage.provider=",
                        "app.object-storage.service-bucket=test-bucket")
                .run(context -> assertThat(context).doesNotHaveBean(DocumentStoragePort.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ObjectStorageProperties.class)
    @org.springframework.context.annotation.Import({
            LocalDocumentStorageAdapter.class, S3DocumentStorage.class})
    static class Adapters {
    }
}
