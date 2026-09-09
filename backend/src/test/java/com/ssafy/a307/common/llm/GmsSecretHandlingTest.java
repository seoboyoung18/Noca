package com.ssafy.a307.common.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>키가 새는 경로를 실제로 막았는지 고정한다.</b>
 *
 * <p>이 테스트가 형식적이면 안 되는 이유는 되돌릴 수 없기 때문이다. 키가 로그·커밋에 한 번
 * 남으면 지워도 이미 노출이다. 그래서 "키를 안 쓴다" 는 약속이 아니라 <b>키를 쓸 수 없는 구조</b>를
 * 확인한다.
 */
@DisplayName("GMS 비밀 취급")
class GmsSecretHandlingTest {

    /** 실제 키가 아니다. 이 문자열이 어디에 새는지 추적하려고 쓰는 표식이다. */
    private static final String SENTINEL = "sentinel-not-a-real-key-9f3a";

    @Test
    @DisplayName("GmsApiKey.toString 은 값을 내지 않는다")
    void apiKeyToStringHidesValue() {
        GmsApiKey key = new GmsApiKey(SENTINEL);

        assertThat(key.toString()).doesNotContain(SENTINEL).isEqualTo("GmsApiKey[present=true]");
        assertThat(new GmsApiKey("").toString()).isEqualTo("GmsApiKey[present=false]");
    }

    @Test
    @DisplayName("GmsProperties 에는 키를 담을 자리가 없다 — toString 에 실릴 수 없다")
    void propertiesCannotCarryTheKey() {
        GmsProperties properties = properties(GmsProvider.GEMINI, "gemini-2.5-flash");

        // record 의 기본 toString 은 모든 구성요소를 찍는다. 키가 구성요소가 아니어야 안전하다.
        assertThat(properties.toString()).doesNotContain(SENTINEL);
        assertThat(GmsProperties.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("apiKey", "key", "secret", "token");
    }

    @Test
    @DisplayName("키가 없으면 값을 꺼낼 수 없다 — 빈 문자열을 헤더에 싣지 않는다")
    void missingKeyFailsFastInsteadOfSendingBlank() {
        GmsApiKey empty = new GmsApiKey("  ");

        assertThat(empty.isPresent()).isFalse();
        assertThatThrownBy(empty::value)
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SENTINEL));
    }

    @Test
    @DisplayName("첨부 toString 에 파일 바이트가 실리지 않는다")
    void attachmentToStringHidesBytes() {
        byte[] content = "차주 홍길동 12가3456".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        LlmChatPort.Attachment attachment = new LlmChatPort.Attachment("application/pdf", content);

        assertThat(attachment.toString())
                .doesNotContain("홍길동")
                .contains("application/pdf")
                .contains("bytes=" + content.length);
    }

    @Test
    @DisplayName("첨부 바이트는 복제해서 담고 복제해서 낸다")
    void attachmentCopiesDefensively() {
        byte[] source = {1, 2, 3};
        LlmChatPort.Attachment attachment = new LlmChatPort.Attachment("image/png", source);

        source[0] = 9;
        attachment.content()[1] = 9;

        assertThat(attachment.content()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("평문 http 기준 URL 은 기동 시점에 걸린다")
    void plainHttpBaseUrlIsRejected() {
        GmsProperties insecure = new GmsProperties(
                "http://gms.ssafy.io/gmsapi", GmsProvider.GEMINI, "gemini-2.5-flash",
                Duration.ofSeconds(1), Duration.ofSeconds(2), 1024, 2, 1, Duration.ofMinutes(5));

        assertThat(insecure.isBaseUrlSecure()).isFalse();
        assertThat(properties(GmsProvider.GEMINI, "gemini-2.5-flash").isBaseUrlSecure()).isTrue();
    }

    static GmsProperties properties(GmsProvider provider, String model) {
        return new GmsProperties(
                "https://gms.ssafy.io/gmsapi", provider, model,
                Duration.ofSeconds(1), Duration.ofSeconds(2), 1024, 2, 1, Duration.ofMinutes(5));
    }
}
