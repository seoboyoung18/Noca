package com.ssafy.a307.common.llm;

import tools.jackson.databind.ObjectMapper;
import com.ssafy.a307.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withTooManyRequests;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

/**
 * 전송 계층. <b>네트워크를 타지 않는다</b> — {@code MockRestServiceServer} 를
 * {@code RestClient.Builder} 에 바인딩해 요청을 가로챈다.
 *
 * <p>실제 GMS 를 부르지 않는 이유는 셋이다. 크레딧이 팀 공유 자원이고, 첫 소비처가 보낼 것이
 * 개인정보이며, 키 없이도 이 작업이 끝나야 하기 때문이다.
 */
@DisplayName("GMS 전송 계층")
class GmsChatClientTest {

    private static final String KEY = "test-key-not-a-real-credential";
    private static final String BASE = "https://gms.ssafy.io/gmsapi";

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ------------------------------------------------------------------ URL · 인증

    @Nested
    @DisplayName("URL 조립과 인증 헤더")
    class Wiring {

        @Test
        @DisplayName("Gemini 는 벤더 호스트와 모델을 경로에 넣고 x-goog-api-key 로 인증한다")
        void geminiUrlAndHeader() {
            Fixture fixture = fixture(GmsProvider.GEMINI, "gemini-2.5-flash", creditOk());
            fixture.server.expect(requestTo(
                            BASE + "/generativelanguage.googleapis.com/v1beta/models/"
                                    + "gemini-2.5-flash:generateContent"))
                    .andExpect(header("x-goog-api-key", KEY))
                    .andExpect(headerDoesNotExist("Authorization"))
                    .andRespond(withSuccess(geminiBody("{\"ok\":true}"), MediaType.APPLICATION_JSON));

            fixture.client.complete(LlmChatPort.ChatRequest.textOnly("지시문", null));

            fixture.server.verify();
        }

        @Test
        @DisplayName("OpenAI 는 Responses 경로를 쓰고 Authorization: Bearer 로 인증한다")
        void openAiUrlAndHeader() {
            Fixture fixture = fixture(GmsProvider.OPENAI, "gpt-4o", creditOk());
            fixture.server.expect(requestTo(BASE + "/api.openai.com/v1/responses"))
                    .andExpect(header("Authorization", "Bearer " + KEY))
                    .andExpect(headerDoesNotExist("x-goog-api-key"))
                    .andExpect(jsonPath("$.model").value("gpt-4o"))
                    .andRespond(withSuccess(openAiBody("{\"ok\":true}"), MediaType.APPLICATION_JSON));

            fixture.client.complete(LlmChatPort.ChatRequest.textOnly("지시문", null));

            fixture.server.verify();
        }

        @Test
        @DisplayName("OpenAI chat 모드는 Chat Completions 경로와 본문 형식을 쓴다")
        void openAiChatUrlHeaderAndBody() {
            Fixture fixture = fixture(GmsProvider.OPENAI, "gpt-5.4", OpenAiApi.CHAT, creditOk());
            fixture.server.expect(requestTo(BASE + "/api.openai.com/v1/chat/completions"))
                    .andExpect(header("Authorization", "Bearer " + KEY))
                    .andExpect(jsonPath("$.model").value("gpt-5.4"))
                    .andExpect(jsonPath("$.messages[0].role").value("developer"))
                    .andExpect(jsonPath("$.messages[0].content").value("지시문"))
                    .andExpect(jsonPath("$.messages[1].role").value("user"))
                    .andExpect(jsonPath("$.max_completion_tokens").value(1024))
                    .andExpect(jsonPath("$.max_output_tokens").doesNotExist())
                    .andRespond(withSuccess(chatBody("{\"ok\":true}"), MediaType.APPLICATION_JSON));

            fixture.client.complete(LlmChatPort.ChatRequest.textOnly("지시문", null));

            fixture.server.verify();
        }

        @Test
        @DisplayName("모델명에 경로 문자가 있으면 요청을 만들지 않는다")
        void rejectsUnsafeModelName() {
            for (String unsafe : List.of("../secret", "models/x", "a:b", "with space", "")) {
                Fixture fixture = fixture(GmsProvider.GEMINI, unsafe, creditOk());

                assertThatThrownBy(() ->
                        fixture.client.complete(LlmChatPort.ChatRequest.textOnly("지시문", null)))
                        .isInstanceOf(IllegalArgumentException.class);
                // 요청이 한 번도 나가지 않았다.
                fixture.server.verify();
            }
        }

        @Test
        @DisplayName("구조화 출력 스키마를 벤더별 자리에 싣는다")
        void carriesResponseSchema() {
            LlmChatPort.JsonSchema schema = new LlmChatPort.JsonSchema(
                    "items", """
                            {"type":"object","properties":{"a":{"type":"string"}},
                             "required":["a"],"additionalProperties":false}
                            """);

            Fixture gemini = fixture(GmsProvider.GEMINI, "gemini-2.5-flash", creditOk());
            gemini.server.expect(requestTo(org.hamcrest.Matchers.containsString("generateContent")))
                    .andExpect(jsonPath("$.generationConfig.responseMimeType").value("application/json"))
                    .andExpect(jsonPath("$.generationConfig.responseSchema.type").value("object"))
                    .andRespond(withSuccess(geminiBody("{\"a\":\"x\"}"), MediaType.APPLICATION_JSON));
            gemini.client.complete(LlmChatPort.ChatRequest.textOnly("지시문", schema));
            gemini.server.verify();

            Fixture openAi = fixture(GmsProvider.OPENAI, "gpt-4o", creditOk());
            openAi.server.expect(requestTo(org.hamcrest.Matchers.containsString("responses")))
                    .andExpect(jsonPath("$.text.format.type").value("json_schema"))
                    .andExpect(jsonPath("$.text.format.name").value("items"))
                    .andRespond(withSuccess(openAiBody("{\"a\":\"x\"}"), MediaType.APPLICATION_JSON));
            openAi.client.complete(LlmChatPort.ChatRequest.textOnly("지시문", schema));
            openAi.server.verify();

            Fixture chat = fixture(GmsProvider.OPENAI, "gpt-5.4", OpenAiApi.CHAT, creditOk());
            chat.server.expect(requestTo(org.hamcrest.Matchers.containsString("chat/completions")))
                    .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                    .andExpect(jsonPath("$.response_format.json_schema.name").value("items"))
                    .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                    .andExpect(jsonPath("$.response_format.json_schema.schema.type").value("object"))
                    .andRespond(withSuccess(chatBody("{\"a\":\"x\"}"), MediaType.APPLICATION_JSON));
            chat.client.complete(LlmChatPort.ChatRequest.textOnly("지시문", schema));
            chat.server.verify();
        }

        @Test
        @DisplayName("PDF 첨부가 벤더별 자리에 실린다 — Gemini 는 inline_data, OpenAI 는 input_file")
        void carriesPdfAttachment() {
            LlmChatPort.Attachment pdf = new LlmChatPort.Attachment(
                    "application/pdf", new byte[]{'%', 'P', 'D', 'F'});

            Fixture gemini = fixture(GmsProvider.GEMINI, "gemini-2.5-flash", creditOk());
            gemini.server.expect(requestTo(org.hamcrest.Matchers.containsString("generateContent")))
                    .andExpect(jsonPath("$.contents[0].parts[1].inline_data.mime_type")
                            .value("application/pdf"))
                    .andRespond(withSuccess(geminiBody("{\"a\":1}"), MediaType.APPLICATION_JSON));
            gemini.client.complete(new LlmChatPort.ChatRequest("지시문", List.of(pdf), null));
            gemini.server.verify();

            Fixture openAi = fixture(GmsProvider.OPENAI, "gpt-4o", creditOk());
            openAi.server.expect(requestTo(org.hamcrest.Matchers.containsString("responses")))
                    .andExpect(jsonPath("$.input[0].content[1].type").value("input_file"))
                    // 원본 파일명을 외부로 흘리지 않는다.
                    .andExpect(jsonPath("$.input[0].content[1].filename").value("attachment.pdf"))
                    .andRespond(withSuccess(openAiBody("{\"a\":1}"), MediaType.APPLICATION_JSON));
            openAi.client.complete(new LlmChatPort.ChatRequest("지시문", List.of(pdf), null));
            openAi.server.verify();

            Fixture chat = fixture(GmsProvider.OPENAI, "gpt-5.4", OpenAiApi.CHAT, creditOk());
            chat.server.expect(requestTo(org.hamcrest.Matchers.containsString("chat/completions")))
                    .andExpect(jsonPath("$.messages[1].content[1].type").value("file"))
                    .andExpect(jsonPath("$.messages[1].content[1].file.filename").value("attachment.pdf"))
                    .andExpect(jsonPath("$.messages[1].content[1].file.file_data")
                            .value("data:application/pdf;base64,JVBERg=="))
                    .andRespond(withSuccess(chatBody("{\"a\":1}"), MediaType.APPLICATION_JSON));
            chat.client.complete(new LlmChatPort.ChatRequest("지시문", List.of(pdf), null));
            chat.server.verify();
        }

        @Test
        @DisplayName("OpenAI chat 모드는 이미지 첨부를 image_url data URI 로 싣는다")
        void carriesChatImageAttachment() {
            LlmChatPort.Attachment image = new LlmChatPort.Attachment(
                    "image/jpeg", new byte[]{1, 2, 3});
            Fixture chat = fixture(GmsProvider.OPENAI, "gpt-5.4", OpenAiApi.CHAT, creditOk());
            chat.server.expect(requestTo(org.hamcrest.Matchers.containsString("chat/completions")))
                    .andExpect(jsonPath("$.messages[1].content[1].type").value("image_url"))
                    .andExpect(jsonPath("$.messages[1].content[1].image_url.url")
                            .value("data:image/jpeg;base64,AQID"))
                    .andRespond(withSuccess(chatBody("{\"a\":1}"), MediaType.APPLICATION_JSON));

            chat.client.complete(new LlmChatPort.ChatRequest("지시문", List.of(image), null));

            chat.server.verify();
        }
    }

    // ------------------------------------------------------------------ 응답 처리

    @Nested
    @DisplayName("구조화 출력 파싱")
    class Parsing {

        @Test
        @DisplayName("정상 JSON 을 모델 이름·토큰 사용량과 함께 낸다")
        void parsesJsonWithMetadata() {
            Fixture fixture = fixture(GmsProvider.GEMINI, "gemini-2.5-flash", creditOk());
            fixture.server.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                    .andRespond(withSuccess("""
                            {"candidates":[{"content":{"parts":[{"text":"{\\"items\\":[1,2]}"}]},
                                            "finishReason":"STOP"}],
                             "usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":40},
                             "modelVersion":"gemini-2.5-flash-001"}
                            """, MediaType.APPLICATION_JSON));

            LlmChatPort.ChatResult result =
                    fixture.client.complete(LlmChatPort.ChatRequest.textOnly("지시문", null));

            assertThat(result.json()).isEqualTo("{\"items\":[1,2]}");
            assertThat(result.model()).isEqualTo("gemini-2.5-flash-001");
            assertThat(result.usage().inputTokens()).isEqualTo(120);
            assertThat(result.usage().outputTokens()).isEqualTo(40);
        }

        @Test
        @DisplayName("OpenAI chat 응답의 JSON·모델·토큰 사용량을 파싱한다")
        void parsesChatJsonWithMetadata() {
            Fixture fixture = fixture(GmsProvider.OPENAI, "gpt-5.4", OpenAiApi.CHAT, creditOk());
            fixture.server.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                    .andRespond(withSuccess("""
                            {"choices":[{"message":{"content":"{\\"items\\":[1,2]}"},
                                          "finish_reason":"stop"}],
                             "usage":{"prompt_tokens":120,"completion_tokens":40,"total_tokens":160},
                             "model":"gpt-5.4-2026-03-05"}
                            """, MediaType.APPLICATION_JSON));

            LlmChatPort.ChatResult result =
                    fixture.client.complete(LlmChatPort.ChatRequest.textOnly("지시문", null));

            assertThat(result.json()).isEqualTo("{\"items\":[1,2]}");
            assertThat(result.model()).isEqualTo("gpt-5.4-2026-03-05");
            assertThat(result.usage().inputTokens()).isEqualTo(120);
            assertThat(result.usage().outputTokens()).isEqualTo(40);
        }

        @Test
        @DisplayName("코드펜스로 감싸 와도 벗겨 낸다")
        void stripsCodeFence() {
            Fixture fixture = fixture(GmsProvider.GEMINI, "gemini-2.5-flash", creditOk());
            fixture.server.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                    .andRespond(withSuccess(
                            geminiBody("여기 있습니다: ```json {\"a\":1} ```"), MediaType.APPLICATION_JSON));

            assertThat(fixture.client.complete(LlmChatPort.ChatRequest.textOnly("지시문", null)).json())
                    .isEqualTo("{\"a\":1}");
        }

        @Test
        @DisplayName("잘린 응답은 빈 응답·형식 위반과 구분한다")
        void detectsTruncation() {
            Fixture fixture = fixture(GmsProvider.GEMINI, "gemini-2.5-flash", creditOk());
            fixture.server.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                    .andRespond(withSuccess("""
                            {"candidates":[{"content":{"parts":[{"text":"{\\"a\\":"}]},
                                            "finishReason":"MAX_TOKENS"}]}
                            """, MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> fixture.client.complete(
                    LlmChatPort.ChatRequest.textOnly("지시문", null)))
                    .isInstanceOfSatisfying(LlmChatException.class, e -> {
                        assertThat(e.reason()).isEqualTo(LlmChatException.Reason.TRUNCATED_RESPONSE);
                        assertThat(e.isRetryable()).isFalse();
                    });
        }

        @Test
        @DisplayName("OpenAI chat finish_reason=length 는 잘린 응답이다")
        void detectsChatTruncation() {
            Fixture fixture = fixture(GmsProvider.OPENAI, "gpt-5.4", OpenAiApi.CHAT, creditOk());
            fixture.server.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                    .andRespond(withSuccess("""
                            {"choices":[{"message":{"content":"{\\"a\\":"},
                                          "finish_reason":"length"}],
                             "model":"gpt-5.4"}
                            """, MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> fixture.client.complete(
                    LlmChatPort.ChatRequest.textOnly("지시문", null)))
                    .isInstanceOfSatisfying(LlmChatException.class, e -> {
                        assertThat(e.reason()).isEqualTo(LlmChatException.Reason.TRUNCATED_RESPONSE);
                        assertThat(e.isRetryable()).isFalse();
                    });
        }

        @Test
        @DisplayName("본문에 텍스트가 없으면 빈 응답이다")
        void detectsEmptyResponse() {
            Fixture fixture = fixture(GmsProvider.GEMINI, "gemini-2.5-flash", creditOk());
            fixture.server.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                    .andRespond(withSuccess("{\"candidates\":[]}", MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> fixture.client.complete(
                    LlmChatPort.ChatRequest.textOnly("지시문", null)))
                    .isInstanceOfSatisfying(LlmChatException.class,
                            e -> assertThat(e.reason()).isEqualTo(LlmChatException.Reason.EMPTY_RESPONSE));
        }

        @Test
        @DisplayName("JSON 이 아니면 형식 위반이고, 예외 메시지에 응답 본문을 담지 않는다")
        void malformedResponseKeepsBodyOutOfMessage() {
            Fixture fixture = fixture(GmsProvider.GEMINI, "gemini-2.5-flash", creditOk());
            fixture.server.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                    .andRespond(withSuccess(
                            geminiBody("차주 홍길동 12가3456 을 읽었습니다"), MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> fixture.client.complete(
                    LlmChatPort.ChatRequest.textOnly("지시문", null)))
                    .isInstanceOfSatisfying(LlmChatException.class, e -> {
                        assertThat(e.reason()).isEqualTo(LlmChatException.Reason.MALFORMED_RESPONSE);
                        assertThat(e.getMessage()).doesNotContain("홍길동").doesNotContain("12가3456");
                    });
        }

        @Test
        @DisplayName("토큰 사용량을 벤더가 주지 않으면 0 으로 지어내지 않는다")
        void unknownUsageIsNotFabricated() {
            Fixture fixture = fixture(GmsProvider.GEMINI, "gemini-2.5-flash", creditOk());
            fixture.server.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                    .andRespond(withSuccess(
                            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"{}\"}]}}]}",
                            MediaType.APPLICATION_JSON));

            LlmChatPort.Usage usage =
                    fixture.client.complete(LlmChatPort.ChatRequest.textOnly("지시문", null)).usage();

            assertThat(usage.isKnown()).isFalse();
            assertThat(usage.inputTokens()).isEqualTo(-1);
        }
    }

    // ------------------------------------------------------------------ 오류 · 재시도

    @Nested
    @DisplayName("오류 매핑과 재시도")
    class Failures {

        @Test
        @DisplayName("401 은 재시도하지 않고, 메시지에 키가 들어가지 않는다")
        void authFailureIsNotRetried() {
            Fixture fixture = fixture(GmsProvider.GEMINI, "gemini-2.5-flash", creditOk());
            fixture.server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                            requestTo(org.hamcrest.Matchers.any(String.class)))
                    .andRespond(withUnauthorizedRequest());

            assertThatThrownBy(() -> fixture.client.complete(
                    LlmChatPort.ChatRequest.textOnly("지시문", null)))
                    .isInstanceOfSatisfying(LlmChatException.class, e -> {
                        assertThat(e.reason()).isEqualTo(LlmChatException.Reason.AUTHENTICATION);
                        assertThat(e.isRetryable()).isFalse();
                        assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("Authorization");
                    });
            fixture.server.verify();
        }

        @Test
        @DisplayName("400 은 한 번만 호출한다 — 재시도가 크레딧을 태운다")
        void badRequestIsCalledOnce() {
            Fixture fixture = fixture(GmsProvider.GEMINI, "gemini-2.5-flash", creditOk());
            fixture.server.expect(org.springframework.test.web.client.ExpectedCount.once(),
                            requestTo(org.hamcrest.Matchers.any(String.class)))
                    .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));

            assertThatThrownBy(() -> fixture.client.complete(
                    LlmChatPort.ChatRequest.textOnly("지시문", null)))
                    .isInstanceOfSatisfying(LlmChatException.class, e -> {
                        assertThat(e.reason()).isEqualTo(LlmChatException.Reason.BAD_REQUEST);
                        assertThat(e.isRetryable()).isFalse();
                    });
            fixture.server.verify();
        }

        @Test
        @DisplayName("429 다음에 성공하면 성공이다")
        void retriesRateLimitThenSucceeds() {
            Fixture fixture = fixture(GmsProvider.GEMINI, "gemini-2.5-flash", creditOk());
            fixture.server.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                    .andRespond(withTooManyRequests());
            fixture.server.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                    .andRespond(withSuccess(geminiBody("{\"a\":1}"), MediaType.APPLICATION_JSON));

            assertThat(fixture.client.complete(LlmChatPort.ChatRequest.textOnly("지시문", null)).json())
                    .isEqualTo("{\"a\":1}");
            fixture.server.verify();
        }

        @Test
        @DisplayName("5xx 가 이어지면 max-attempts 만큼만 부르고 포기한다")
        void stopsAtMaxAttempts() {
            Fixture fixture = fixture(GmsProvider.GEMINI, "gemini-2.5-flash", creditOk());
            fixture.server.expect(org.springframework.test.web.client.ExpectedCount.times(2),
                            requestTo(org.hamcrest.Matchers.any(String.class)))
                    .andRespond(withServerError());

            assertThatThrownBy(() -> fixture.client.complete(
                    LlmChatPort.ChatRequest.textOnly("지시문", null)))
                    .isInstanceOfSatisfying(LlmChatException.class,
                            e -> assertThat(e.reason()).isEqualTo(LlmChatException.Reason.UPSTREAM_ERROR));
            fixture.server.verify();
        }
    }

    // ------------------------------------------------------------------ 크레딧 가드

    @Nested
    @DisplayName("크레딧 가드")
    class Credit {

        @Test
        @DisplayName("잔여 크레딧이 임계값 미만이면 전송이 한 번도 일어나지 않는다")
        void refusesWithoutSending() {
            Fixture fixture = fixture(GmsProvider.GEMINI, "gemini-2.5-flash",
                    keyInfo(0L, "2099-12-31"));

            assertThatThrownBy(() -> fixture.client.complete(
                    LlmChatPort.ChatRequest.textOnly("지시문", null)))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode())
                                .isEqualTo(com.ssafy.a307.common.exception.ErrorCode.SERVICE_UNAVAILABLE);
                        // 잔액·만료일·키를 사용자 메시지에 담지 않는다.
                        assertThat(e.getMessage()).doesNotContain("0").doesNotContain("2099");
                    });
            // 채팅 대역에 아무 요청도 오지 않았다.
            fixture.server.verify();
        }

        @Test
        @DisplayName("만료된 키는 거절한다")
        void refusesExpiredKey() {
            Fixture fixture = fixture(GmsProvider.GEMINI, "gemini-2.5-flash",
                    keyInfo(1_000L, "2020-01-01"));

            assertThatThrownBy(() -> fixture.client.complete(
                    LlmChatPort.ChatRequest.textOnly("지시문", null)))
                    .isInstanceOf(BusinessException.class);
            fixture.server.verify();
        }

        @Test
        @DisplayName("만료일 형식을 해석하지 못하면 판정을 건너뛴다 — 멀쩡한 키를 막지 않는다")
        void unparseableExpiryDoesNotBlock() {
            Fixture fixture = fixture(GmsProvider.GEMINI, "gemini-2.5-flash",
                    keyInfo(1_000L, "언젠가 만료됨"));
            fixture.server.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                    .andRespond(withSuccess(geminiBody("{\"a\":1}"), MediaType.APPLICATION_JSON));

            assertThat(fixture.client.complete(LlmChatPort.ChatRequest.textOnly("지시문", null)).json())
                    .isEqualTo("{\"a\":1}");
        }

        @Test
        @DisplayName("크레딧 조회가 실패해도 본 호출을 막지 않는다")
        void keyInfoFailureDoesNotBlock() {
            Fixture fixture = fixture(GmsProvider.GEMINI, "gemini-2.5-flash", KeyInfoStub.failing());
            fixture.server.expect(requestTo(org.hamcrest.Matchers.any(String.class)))
                    .andRespond(withSuccess(geminiBody("{\"a\":1}"), MediaType.APPLICATION_JSON));

            assertThat(fixture.client.complete(LlmChatPort.ChatRequest.textOnly("지시문", null)).json())
                    .isEqualTo("{\"a\":1}");
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * {@code MockRestServiceServer} 를 채팅용 빌더에 바인딩한다.
     *
     * <p>크레딧 조회는 별도 대역({@link KeyInfoStub})으로 둔다. 같은 서버에 섞으면
     * "크레딧 때문에 호출이 안 나갔는지" 를 단언할 수 없다 — 요청 하나가 어느 쪽인지 구분되지 않는다.
     */
    private Fixture fixture(GmsProvider provider, String model, KeyInfoStub keyInfo) {
        return fixture(provider, model, OpenAiApi.RESPONSES, keyInfo);
    }

    private Fixture fixture(
            GmsProvider provider, String model, OpenAiApi openAiApi, KeyInfoStub keyInfo) {
        GmsProperties properties = new GmsProperties(
                BASE, provider, model, openAiApi, Duration.ofSeconds(1), Duration.ofSeconds(2),
                1024, 2, 1, Duration.ofMinutes(5));
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        GmsCreditGuard guard = new GmsCreditGuard(
                keyInfo, properties, Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC));
        GmsApiKey apiKey = new GmsApiKey(KEY);
        LlmChatPort client = provider == GmsProvider.GEMINI
                ? new GeminiGmsClient(properties, apiKey, objectMapper, guard, builder)
                : new OpenAiGmsClient(properties, apiKey, objectMapper, guard, builder);
        return new Fixture(client, server);
    }

    private record Fixture(LlmChatPort client, MockRestServiceServer server) {
    }

    /** {@code key-info} 를 HTTP 없이 흉내낸다. 조회 실패도 재현한다. */
    static class KeyInfoStub extends GmsKeyInfoClient {

        private final GmsKeyInfo info;

        private KeyInfoStub(GmsKeyInfo info) {
            super(GmsSecretHandlingTest.properties(GmsProvider.GEMINI, "m"),
                    new GmsApiKey("stub"), RestClient.builder());
            this.info = info;
        }

        static KeyInfoStub of(GmsKeyInfo info) {
            return new KeyInfoStub(info);
        }

        static KeyInfoStub failing() {
            return new KeyInfoStub(null);
        }

        @Override
        GmsKeyInfo fetch() {
            if (info == null) throw new IllegalStateException("key-info 조회 실패(테스트)");
            return info;
        }
    }

    private static KeyInfoStub creditOk() {
        return keyInfo(10_000L, "2099-12-31");
    }

    private static KeyInfoStub keyInfo(long remain, String expiredDate) {
        return KeyInfoStub.of(new GmsKeyInfo(10_000L, 10_000L - remain, remain, expiredDate));
    }

    private static String geminiBody(String text) {
        return """
                {"candidates":[{"content":{"parts":[{"text":"%s"}]},"finishReason":"STOP"}],
                 "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1},
                 "modelVersion":"gemini-2.5-flash"}
                """.formatted(text.replace("\"", "\\\""));
    }

    private static String openAiBody(String text) {
        return """
                {"output":[{"type":"message","content":[{"type":"output_text","text":"%s"}]}],
                 "usage":{"input_tokens":1,"output_tokens":1},
                 "model":"gpt-4o"}
                """.formatted(text.replace("\"", "\\\""));
    }

    private static String chatBody(String text) {
        return """
                {"choices":[{"message":{"content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":1,"completion_tokens":1},
                 "model":"gpt-5.4"}
                """.formatted(text.replace("\"", "\\\""));
    }
}
