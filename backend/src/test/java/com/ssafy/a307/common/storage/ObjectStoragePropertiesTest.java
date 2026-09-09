package com.ssafy.a307.common.storage;

import com.ssafy.a307.estimatevalidation.file.DocumentStoragePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 공용 오브젝트 저장소 설정.
 *
 * <p><b>테스트 환경이 AWS 를 요구하지 않는 것</b>을 고정한다. 테스트 클래스패스의
 * {@code application.properties} 가 main 쪽을 가리므로 여기서 버킷을 비워 두면 어댑터 빈이
 * 만들어지지 않고, 소비자가 받는 {@code Optional} 이 비어 503 이 나간다. 누군가 테스트
 * 리소스에 버킷을 채우면 CI 가 자격증명을 요구하기 시작하는데, 그 순간 이 테스트가 먼저 깨진다.
 */
@DisplayName("공용 오브젝트 저장소 설정")
class ObjectStoragePropertiesTest {

    @Nested
    @DisplayName("설정 판정")
    class Judgement {

        @Test
        @DisplayName("리전과 두 버킷이 다 있어야 완전한 설정이다")
        void requiresRegionAndBothBuckets() {
            assertThat(new ObjectStorageProperties("ap-northeast-2", "s", "v").isConfigured()).isTrue();
            assertThat(new ObjectStorageProperties("ap-northeast-2", "", "v").isConfigured()).isFalse();
            assertThat(new ObjectStorageProperties("ap-northeast-2", "s", "").isConfigured()).isFalse();
            assertThat(new ObjectStorageProperties("", "s", "v").isConfigured()).isFalse();
        }

        @Test
        @DisplayName("서버 경유로만 올리는 어댑터는 서비스 버킷만 있으면 된다")
        void serviceBucketAloneIsEnoughForServerSideUpload() {
            assertThat(new ObjectStorageProperties("ap-northeast-2", "", "v").hasServiceBucket()).isTrue();
            assertThat(new ObjectStorageProperties("ap-northeast-2", "s", "").hasServiceBucket()).isFalse();
            assertThat(new ObjectStorageProperties(null, "s", "v").hasServiceBucket()).isFalse();
        }

        @Test
        @DisplayName("공백만 있는 값은 없는 것으로 센다 — 빈 문자열 함정")
        void blankCountsAsMissing() {
            assertThat(new ObjectStorageProperties("  ", "  ", "  ").isConfigured()).isFalse();
            assertThat(new ObjectStorageProperties("ap-northeast-2", "s", "  ").hasServiceBucket()).isFalse();
        }
    }

    @Nested
    @SpringBootTest
    @DisplayName("테스트 환경")
    class InTestEnvironment {

        @Autowired ObjectStorageProperties properties;
        @Autowired ApplicationContext context;
        @Autowired Optional<DocumentStoragePort> storagePort;

        @Test
        @DisplayName("설정이 바인딩되고 리전은 채워져 있다 — GetBucketLocation 을 부르지 않기 위해서다")
        void regionIsAlwaysPresent() {
            assertThat(properties.region()).isNotBlank();
        }

        @Test
        @DisplayName("버킷이 비어 있어 테스트가 AWS 자격증명을 요구하지 않는다")
        void bucketsAreEmptyInTests() {
            assertThat(properties.hasServiceBucket()).isFalse();
            assertThat(properties.isConfigured()).isFalse();
        }

        @Test
        @DisplayName("저장소 어댑터가 하나도 뜨지 않는다 — 파일 업로드는 503 이다")
        void noStorageAdapterIsCreated() {
            assertThat(storagePort).isEmpty();
            assertThat(context.getBeanNamesForType(DocumentStoragePort.class)).isEmpty();
        }

        @Test
        @DisplayName("워커 두 개(검증·PDF)가 모두 꺼져 있다 — 테스트 중 임의로 돌면 다른 테스트가 흔들린다")
        void bothWorkersAreOff() {
            assertThat(context.getEnvironment().getProperty("app.estimate-worker.enabled"))
                    .isEqualTo("false");
            assertThat(context.getEnvironment().getProperty("app.validation-pdf.enabled"))
                    .isEqualTo("false");
        }
    }
}
