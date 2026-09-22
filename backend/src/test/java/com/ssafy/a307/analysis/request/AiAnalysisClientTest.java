package com.ssafy.a307.analysis.request;

import com.ssafy.a307.analysis.callback.InternalApiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * AI 서버 {@code /analyze} 호출 (S15P21A307-156). <b>네트워크를 타지 않는다</b> —
 * {@code MockRestServiceServer} 를 빌더에 묶는다({@code KakaoLocalClientTest} 와 같은 방식).
 *
 * <p><b>요청 본문 모양은 이 테스트가 지킨다.</b> AI 쪽 입력 형식이 바뀌면
 * {@link AnalysisRequestPayload} 와 함께 여기를 고친다.
 */
@DisplayName("AI 분석 요청 클라이언트 (S15P21A307-156)")
class AiAnalysisClientTest {

    private static final String ANALYZE_URL = "http://ai.test/analyze";

    private MockRestServiceServer server;
    private AiAnalysisClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        AnalysisRequestProperties properties = new AnalysisRequestProperties(false,
                "http://ai.test/", "http://backend.test", 5,
                Duration.ofMinutes(10), Duration.ofSeconds(2), Duration.ofSeconds(5));
        client = new AiAnalysisClient(properties, new InternalApiProperties("shared-secret"),
                new ObjectMapper(), builder);
    }

    @Test
    @DisplayName("계약 ④ 모양으로 보낸다 — 토큰 헤더, 작업·차량·사진·콜백 주소")
    void sendsContractBody() {
        server.expect(once(), requestTo(ANALYZE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(AiAnalysisClient.TOKEN_HEADER, "shared-secret"))
                .andExpect(jsonPath("$.jobId").value(12))
                .andExpect(jsonPath("$.requestId").value("a1b2c3d4e5f6"))
                .andExpect(jsonPath("$.vehicle.modelId").value(41))
                .andExpect(jsonPath("$.vehicle.manufacturer").value("현대"))
                .andExpect(jsonPath("$.vehicle.modelName").value("아반떼"))
                .andExpect(jsonPath("$.vehicle.carClass").value("Compact"))
                .andExpect(jsonPath("$.vehicle.modelYear").value(2021))
                .andExpect(jsonPath("$.images[0].imageId").value(501))
                .andExpect(jsonPath("$.images[0].angleCode").value("REAR_LEFT"))
                .andExpect(jsonPath("$.images[0].url").value("https://s3.test/resized-501.jpg"))
                .andExpect(jsonPath("$.images[0].expiresAt").value("2026-09-10T16:20:00Z"))
                .andExpect(jsonPath("$.callbackUrl")
                        .value("http://backend.test/internal/analysis-jobs/12/result"))
                // 부위를 고르지 않은 분석의 본문은 이 필드가 생기기 전과 같다 (S15P21A307-570)
                .andExpect(jsonPath("$.selectedPartCode").doesNotExist())
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"accepted\":true,\"jobId\":12,\"requestId\":\"a1b2c3d4e5f6\"}"));

        client.analyze(payload());

        server.verify();
    }

    /** 필드 이름은 AI 담당 확인 전이다. 바뀌면 {@link AnalysisRequestPayload} 와 이 테스트만 고친다. */
    @Test
    @DisplayName("사용자가 고른 부위가 있으면 selectedPartCode 로 싣는다 (S15P21A307-570)")
    void sendsSelectedPartCode() {
        server.expect(once(), requestTo(ANALYZE_URL))
                .andExpect(jsonPath("$.jobId").value(12))
                .andExpect(jsonPath("$.selectedPartCode").value("FRONT_FENDER_L"))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"accepted\":true,\"jobId\":12,\"requestId\":\"a1b2c3d4e5f6\"}"));

        client.analyze(payload("FRONT_FENDER_L"));

        server.verify();
    }

    @Test
    @DisplayName("계약의 오류 본문 {code} 를 실패 사유로 쓴다")
    void contractErrorCodeBecomesFailureCode() {
        server.expect(once(), requestTo(ANALYZE_URL))
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"IMAGE_FETCH_FAILED\",\"message\":\"만료\",\"requestId\":\"a1\"}"));

        assertThat(failureOf()).isEqualTo("IMAGE_FETCH_FAILED");
    }

    @Test
    @DisplayName("FastAPI 모양 {detail:{code}} 도 읽는다 — 지금 AI 서버의 501 스텁이 이 모양이다")
    void fastApiDetailCodeIsRead() {
        server.expect(once(), requestTo(ANALYZE_URL))
                .andRespond(withStatus(HttpStatus.NOT_IMPLEMENTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":{\"code\":\"ANALYSIS_ORCHESTRATOR_NOT_IMPLEMENTED\"}}"));

        assertThat(failureOf()).isEqualTo("ANALYSIS_ORCHESTRATOR_NOT_IMPLEMENTED");
    }

    @Test
    @DisplayName("본문이 JSON 이 아니면 상태 코드만 남긴다")
    void nonJsonErrorKeepsStatus() {
        server.expect(once(), requestTo(ANALYZE_URL))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.TEXT_HTML)
                        .body("<html>gateway error</html>"));

        assertThat(failureOf()).isEqualTo("AI_HTTP_502");
    }

    @Test
    @DisplayName("닿지 않거나 제한 시간을 넘기면 AI_UNREACHABLE 이다")
    void ioFailureIsUnreachable() {
        server.expect(once(), requestTo(ANALYZE_URL))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThat(failureOf()).isEqualTo(AnalysisRequestFailure.AI_UNREACHABLE.name());
    }

    private String failureOf() {
        AiAnalysisException e = catchThrowableOfType(AiAnalysisException.class,
                () -> client.analyze(payload()));
        assertThat(e).isNotNull();
        server.verify();
        return e.failureCode();
    }

    private static AnalysisRequestPayload payload() {
        return payload(null);
    }

    private static AnalysisRequestPayload payload(String selectedPartCode) {
        return new AnalysisRequestPayload(12L, "a1b2c3d4e5f6",
                new AnalysisRequestPayload.Vehicle(41L, "현대", "아반떼", "Compact", 2021),
                List.of(new AnalysisRequestPayload.Image(501L, "REAR_LEFT",
                        "https://s3.test/resized-501.jpg", "2026-09-10T16:20:00Z")),
                "http://backend.test/internal/analysis-jobs/12/result", selectedPartCode);
    }
}
