package com.ssafy.a307.common.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 벤더 구현 둘이 공유하는 전송 골격 — 요청 전송, 오류 매핑, 제한된 재시도, 크레딧 가드 호출,
 * 구조화 출력 파싱.
 *
 * <p><b>이 저장소의 첫 외부 HTTP 클라이언트다.</b> 뒤에 오는 연동이 이 코드를 본보기로 삼는다.
 * 그래서 세 가지를 명시적으로 지킨다 — <b>타임아웃을 반드시 설정하고</b>,
 * <b>재시도는 상한이 있고 특정 오류에만 걸며</b>, <b>본문을 로그에 남기지 않는다.</b>
 *
 * <p><b>벤더가 채우는 것은 네 가지뿐이다</b> — 경로, 인증 헤더, 요청 본문, 응답에서 텍스트와
 * 사용량을 꺼내는 방법. 그 밖의 모든 것이 여기 있다.
 */
@Slf4j
abstract class GmsChatClientSupport implements LlmChatPort {

    /** 첫 재시도 전 대기. 짧게 잡는 이유는 아래 Javadoc 에 있다. */
    private static final long BASE_BACKOFF_MILLIS = 200L;
    private static final long MAX_BACKOFF_MILLIS = 2_000L;

    protected final GmsProperties properties;
    protected final GmsApiKey apiKey;
    protected final ObjectMapper objectMapper;

    private final GmsCreditGuard creditGuard;
    private final StructuredJsonReader jsonReader;
    private final RestClient restClient;

    protected GmsChatClientSupport(
            GmsProperties properties,
            GmsApiKey apiKey,
            ObjectMapper objectMapper,
            GmsCreditGuard creditGuard,
            RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.creditGuard = creditGuard;
        this.jsonReader = new StructuredJsonReader(objectMapper);
        // 기준 URL 만 여기서 건다. 타임아웃을 심은 요청 팩터리는 GmsRestClientFactory 가 이미
        // 이 빌더에 꽂아 두었다 — 여기서 다시 setter 를 부르면 테스트가 끼운 HTTP 대역을 덮어쓴다.
        this.restClient = restClientBuilder
                .baseUrl(properties.normalizedBaseUrl())
                .build();
    }

    // ------------------------------------------------------------------ 벤더가 채우는 자리

    protected abstract GmsProvider provider();

    /** 기준 URL 뒤에 붙는 경로. 벤더의 원래 호스트를 포함한다 — GMS 가 프록시이기 때문이다. */
    protected abstract String path(String model);

    /** 인증 헤더. OpenAI 와 Gemini 가 서로 다른 유일한 필연적 이유다. */
    protected abstract Map<String, String> authHeaders(String key);

    protected abstract Map<String, Object> requestBody(ChatRequest request);

    /** 응답에서 모델 출력 텍스트를 꺼낸다. 없으면 null — 빈 응답으로 처리된다. */
    protected abstract String responseText(JsonNode response);

    /** 출력 토큰 상한에 걸려 잘렸는가. 잘린 응답은 스키마를 만족할 수 없다. */
    protected abstract boolean isTruncated(JsonNode response);

    protected abstract Usage usage(JsonNode response);

    /** 벤더가 실제로 응답한 모델 이름. 없으면 설정값으로 갈음한다. */
    protected abstract String respondedModel(JsonNode response);

    // ------------------------------------------------------------------ 공통 흐름

    @Override
    public ChatResult complete(ChatRequest request) {
        // 크레딧이 없으면 아예 보내지 않는다. 보내 봐야 실패하고, 실패도 요청이다.
        creditGuard.ensureUsable();

        long startedAt = System.currentTimeMillis();
        JsonNode response = send(request);

        if (isTruncated(response)) {
            throw new LlmChatException(
                    LlmChatException.Reason.TRUNCATED_RESPONSE, false,
                    "출력 토큰 상한에 걸려 응답이 잘렸다 (max-output-tokens=%d)"
                            .formatted(properties.maxOutputTokens()));
        }
        String json = jsonReader.read(responseText(response));
        Usage usage = usage(response);

        log.info("LLM 호출 완료: provider={}, model={}, {}ms, 입력토큰={}, 출력토큰={}",
                provider(), respondedModel(response), System.currentTimeMillis() - startedAt,
                usage.inputTokens(), usage.outputTokens());
        return new ChatResult(json, respondedModel(response), usage);
    }

    /**
     * 제한된 재시도.
     *
     * <p><b>429·5xx·타임아웃에만 다시 보낸다.</b> 400 계열은 같은 요청을 다시 보내면 똑같이
     * 실패하면서 크레딧만 태운다 — 팀 공유 키라 남의 실습을 막는다.
     *
     * <p><b>백오프를 짧게 잡았다.</b> 소비처가 순차 처리하는 워커라 대기가 곧 처리 정지다.
     * 지수 백오프에 지터를 주되 상한을 {@value #MAX_BACKOFF_MILLIS}ms 로 묶었고, 시도 횟수도
     * {@code max-attempts}(상한 3)로 제한한다.
     */
    private JsonNode send(ChatRequest request) {
        Map<String, Object> body = requestBody(request);
        LlmChatException last = null;

        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                JsonNode response = restClient.post()
                        .uri(path(properties.model()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .headers(headers -> authHeaders(apiKey.value()).forEach(headers::set))
                        .body(body)
                        .retrieve()
                        .body(JsonNode.class);
                if (response == null) {
                    throw new LlmChatException(
                            LlmChatException.Reason.EMPTY_RESPONSE, false, "응답 본문이 없다");
                }
                return response;
            } catch (RestClientResponseException e) {
                last = mapStatus(e.getStatusCode().value(), e);
            } catch (ResourceAccessException e) {
                last = new LlmChatException(
                        LlmChatException.Reason.TRANSPORT, true, "LLM 호출에 실패했습니다.", e);
            } catch (LlmChatException e) {
                last = e;
            }

            // 본문은 남기지 않는다. 남기는 것은 벤더·시도 횟수·사유뿐이다.
            log.warn("LLM 호출 실패: provider={}, 시도={}/{}, 사유={}, 재시도가능={}",
                    provider(), attempt, properties.maxAttempts(), last.reason(), last.isRetryable());
            if (!last.isRetryable()) throw last;
            if (attempt < properties.maxAttempts()) backoff(attempt);
        }
        throw last;
    }

    /**
     * 상태 코드로만 매핑한다.
     *
     * <p><b>응답 본문을 읽지 않는다.</b> GMS 가 벤더 오류를 그대로 넘기는지 감싸는지 확인되지
     * 않았다. 확인되지 않은 형식에 맞춰 분기를 지어 넣으면 틀렸을 때 조용히 잘못 분류된다.
     * 상태 코드는 HTTP 표준이라 추측이 아니다.
     */
    private static LlmChatException mapStatus(int status, Throwable cause) {
        if (status == 401 || status == 403) {
            // 키 값도 헤더 이름도 담지 않는다.
            return new LlmChatException(
                    LlmChatException.Reason.AUTHENTICATION, false,
                    "LLM 서비스를 사용할 수 없습니다. 관리자에게 문의해 주세요.", cause);
        }
        if (status == 429) {
            return new LlmChatException(
                    LlmChatException.Reason.RATE_LIMITED, true, "LLM 호출이 일시적으로 제한되었습니다.", cause);
        }
        if (status >= 500) {
            return new LlmChatException(
                    LlmChatException.Reason.UPSTREAM_ERROR, true, "LLM 서비스가 응답하지 않습니다.", cause);
        }
        return new LlmChatException(
                LlmChatException.Reason.BAD_REQUEST, false, "LLM 호출 요청이 거부되었습니다.", cause);
    }

    private static void backoff(int attempt) {
        long exponential = Math.min(BASE_BACKOFF_MILLIS << (attempt - 1), MAX_BACKOFF_MILLIS);
        long jittered = ThreadLocalRandom.current().nextLong(BASE_BACKOFF_MILLIS, exponential + 1);
        try {
            Thread.sleep(jittered);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmChatException(
                    LlmChatException.Reason.TRANSPORT, false, "LLM 호출이 중단되었습니다.", e);
        }
    }

    /**
     * 경로에 들어가는 모델 이름을 검증한다.
     *
     * <p>Gemini 는 모델명이 <b>URL 경로</b>에 들어간다. 설정값에 {@code /} 나 {@code ..} 가 섞이면
     * 프록시 안에서 다른 엔드포인트로 새어 나갈 수 있다. 문자열을 이어 붙이기 전에 막는다.
     */
    protected static String requireSafePathSegment(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        String value = model.strip();
        if (!value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    "app.gms.model 에 경로로 쓸 수 없는 문자가 있다: 영문·숫자·. _ - 만 허용한다");
        }
        return value;
    }

    protected static String dataUri(String mediaType, byte[] content) {
        return "data:" + mediaType.toLowerCase(Locale.ROOT) + ";base64,"
                + java.util.Base64.getEncoder().encodeToString(content);
    }
}
