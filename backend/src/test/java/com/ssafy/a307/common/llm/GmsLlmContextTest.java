package com.ssafy.a307.common.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>키가 없는 환경에서 애플리케이션이 정상 기동하는지</b>를 고정한다.
 *
 * <p>이 저장소의 관례는 {@code Optional<Port>} 주입 + 없으면 503 이다
 * ({@code EstimateFileValidationService.storage()}, {@code AccidentImageService}). 같은 형태를
 * 유지하는 것이 요점이고, 그 반대편 — <b>키가 없다고 컨텍스트가 안 뜨는 것</b> — 이 실제 위험이다.
 * LLM 은 이 서비스의 곁가지인데 그것 때문에 차량·사고 API 까지 죽으면 안 된다.
 *
 * <p>테스트 클래스패스의 {@code application.properties} 가 {@code app.gms.api-key} 를 비워 두므로
 * 이 테스트는 <b>키가 없는 상태</b>를 그대로 재현한다.
 */
@DisplayName("GMS LLM 계층 기동")
class GmsLlmContextTest {

    @Nested
    @SpringBootTest
    @DisplayName("키가 없을 때")
    class WithoutKey {

        @Autowired ApplicationContext context;
        @Autowired Optional<LlmChatPort> llmChatPort;

        @Test
        @DisplayName("컨텍스트가 정상적으로 뜬다 — 나머지 API 를 죽이지 않는다")
        void contextStartsWithoutKey() {
            assertThat(context).isNotNull();
            assertThat(context.getBean(com.ssafy.a307.common.security.CurrentMemberProvider.class))
                    .isNotNull();
        }

        @Test
        @DisplayName("LLM 전송 빈이 아예 없다 — 소비처는 Optional 로 받아 503 을 주면 된다")
        void noTransportBeanIsCreated() {
            assertThat(llmChatPort).isEmpty();
            assertThat(context.getBeanNamesForType(LlmChatPort.class)).isEmpty();
            assertThat(context.getBeanNamesForType(GmsCreditGuard.class)).isEmpty();
            assertThat(context.getBeanNamesForType(GmsApiKey.class)).isEmpty();
        }

        @Test
        @DisplayName("설정 자체는 바인딩된다 — 값이 틀리면 키와 무관하게 기동에서 걸려야 한다")
        void propertiesStillBind() {
            GmsProperties properties = context.getBean(GmsProperties.class);

            assertThat(properties.provider()).isEqualTo(GmsProvider.GEMINI);
            assertThat(properties.isBaseUrlSecure()).isTrue();
            assertThat(properties.maxAttempts()).isBetween(1, 3);
        }
    }

    /**
     * <b>기준 URL 을 루프백으로 돌린 것은 의도다.</b> 키가 있으면 {@code GmsCreditGuard} 가
     * 기동 시 {@code key-info} 를 한 번 부른다(7-4). 그 호출이 실제 GMS 로 나가면 이 테스트가
     * 네트워크를 타고 팀 크레딧에 손을 대는 셈이 된다. 닫힌 포트로 보내면 즉시 연결이 거부되고,
     * 가드가 "조회 실패 시 본 작업을 막지 않는다" 는 규칙대로 경고만 남기는 것까지 함께 확인된다.
     */
    @Nested
    @SpringBootTest(properties = {
            "app.gms.api-key=context-test-key-not-real",
            "app.gms.base-url=https://127.0.0.1:1",
            "app.gms.connect-timeout=PT1S",
            "app.gms.read-timeout=PT1S"
    })
    @DisplayName("키가 있을 때")
    class WithKey {

        @Autowired ApplicationContext context;
        @Autowired Optional<LlmChatPort> llmChatPort;

        @Test
        @DisplayName("설정한 벤더 구현 하나만 뜬다")
        void singleVendorBeanIsCreated() {
            assertThat(llmChatPort).isPresent();
            assertThat(llmChatPort.get()).isInstanceOf(GeminiGmsClient.class);
            assertThat(context.getBeanNamesForType(LlmChatPort.class)).hasSize(1);
        }

        @Test
        @DisplayName("키 홀더는 값을 toString 으로 내지 않는다")
        void keyHolderHidesValue() {
            GmsApiKey apiKey = context.getBean(GmsApiKey.class);

            assertThat(apiKey.isPresent()).isTrue();
            assertThat(apiKey.toString()).doesNotContain("context-test-key-not-real");
        }
    }
}
