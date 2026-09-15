package com.ssafy.a307.analysis.request;

import com.ssafy.a307.analysis.callback.InternalApiProperties;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST {AI}/analyze} 를 부른다 (S15P21A307-156).
 *
 * <p><b>재시도하지 않는다.</b> 실패하면 작업을 {@code FAILED} 로 끝내고, 다시 시도할지는
 * S15P21A307-161 이 정한다. 여기서 몰래 다시 보내면 AI 가 같은 작업을 두 번 분석할 수 있다.
 *
 * <p>AI 서버 오류 응답은 두 모양이 있다. 계약은 {@code {"code": ...}} 이고, FastAPI 의
 * {@code HTTPException(detail={...})} 는 {@code {"detail": {"code": ...}}} 로 나간다
 * (2026-09-15 AI 서버 골격이 이렇다). 둘 다 읽는다.
 */
public class AiAnalysisClient {

    public static final String TOKEN_HEADER = "X-Internal-Token";

    private final AnalysisRequestProperties properties;
    private final InternalApiProperties internalApi;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    /**
     * @param builder 타임아웃을 심은 빌더. 테스트는 {@code MockRestServiceServer} 를 묶은 빌더를 넘긴다
     */
    public AiAnalysisClient(AnalysisRequestProperties properties, InternalApiProperties internalApi,
                            ObjectMapper objectMapper, RestClient.Builder builder) {
        this.properties = properties;
        this.internalApi = internalApi;
        this.objectMapper = objectMapper;
        this.restClient = builder.build();
    }

    /**
     * 분석을 맡긴다. AI 가 2xx(계약상 202)로 받으면 끝이다 — 결과는 나중에 callback 으로 온다.
     *
     * @throws AiAnalysisException AI 가 거절했거나 닿지 않았다
     */
    public void analyze(AnalysisRequestPayload payload) {
        try {
            restClient.post()
                    .uri(properties.analyzeUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(TOKEN_HEADER, internalApi.token())
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new AiAnalysisException(failureCode(e),
                    "AI 서버가 분석 요청을 거절했다: HTTP " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            throw new AiAnalysisException(AnalysisRequestFailure.AI_UNREACHABLE.name(),
                    "AI 서버에 닿지 않는다", e);
        }
    }

    private String failureCode(RestClientResponseException e) {
        String fallback = "AI_HTTP_" + e.getStatusCode().value();
        try {
            JsonNode body = objectMapper.readTree(e.getResponseBodyAsString());
            if (body.path("code").isString()) {
                return body.path("code").asString();
            }
            if (body.path("detail").path("code").isString()) {
                return body.path("detail").path("code").asString();
            }
            return fallback;
        } catch (RuntimeException notJson) {
            // HTML 게이트웨이 오류처럼 JSON 이 아니면 상태 코드만 남긴다.
            return fallback;
        }
    }
}
