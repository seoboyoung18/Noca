package com.ssafy.a307.analysis;

import com.ssafy.a307.analysis.callback.AnalysisCallbackController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 서버 → 백엔드 분석 결과 수신 (S15P21A307-157) — 수신 골격.
 *
 * <p>이 테스트가 지키는 것은 <b>멱등성과 은닉</b>이다. 결과 저장은 다음 커밋이고, 여기서는
 * 작업 상태가 옳게 옮겨지는지와 잘못된 요청이 막히는지만 본다.
 *
 * <p>세션을 만들지 않는다 — 부르는 쪽이 사용자가 아니라 AI 서버다. 그래서 다른 API 테스트와
 * 달리 {@code MockHttpSession} 설정이 없다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("분석 결과 수신 callback (S15P21A307-157)")
class AnalysisCallbackApiTest {

    private static final long JOB_ID = 98_301L;
    private static final long MEMBER_ID = 98_301L;
    private static final String TOKEN = "test-internal-token";
    private static final String REQUEST_ID = "a1b2c3d4e5f6";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname)
                values (?, 'KAKAO', 'callback-owner', '수신주인')
                """, MEMBER_ID);
        jdbcTemplate.update("""
                insert into vehicle_model (model_id, manufacturer, model_name, vehicle_type, car_class)
                values (98301, '현대', '수신테스트차', 'SEDAN', 'Mid-size')
                """);
        jdbcTemplate.update("""
                insert into vehicle (vehicle_id, member_id, model_id, model_year)
                values (98301, ?, 98301, 2021)
                """, MEMBER_ID);
        jdbcTemplate.update("""
                insert into accident (accident_id, vehicle_id, vehicle_input_type, snapshot_model_id,
                                      snapshot_manufacturer, snapshot_model_name, snapshot_vehicle_type,
                                      snapshot_car_class, snapshot_model_year)
                values (98301, 98301, 'REGISTERED', 98301, '현대', '수신테스트차', 'SEDAN', 'Mid-size', 2021)
                """);
        jdbcTemplate.update("""
                insert into analysis_job (job_id, accident_id, status)
                values (?, 98301, 'PROCESSING')
                """, JOB_ID);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from analysis_job where job_id = ?", JOB_ID);
        jdbcTemplate.update("delete from accident where accident_id = 98301");
        jdbcTemplate.update("delete from vehicle where vehicle_id = 98301");
        jdbcTemplate.update("delete from vehicle_model where model_id = 98301");
        jdbcTemplate.update("delete from member where member_id = ?", MEMBER_ID);
    }

    // ── 은닉 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("토큰이 틀리면 401 이 아니라 404 다 — 경로의 존재를 알려주지 않는다")
    void wrongTokenIsHiddenAs404() throws Exception {
        mockMvc.perform(callback(JOB_ID, REQUEST_ID, successBody(JOB_ID, REQUEST_ID))
                        .header(AnalysisCallbackController.TOKEN_HEADER, "틀린토큰"))
                .andExpect(status().isNotFound());

        assertThat(jobStatus(JOB_ID)).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("토큰이 아예 없어도 404 다")
    void missingTokenIsHiddenAs404() throws Exception {
        mockMvc.perform(callback(JOB_ID, REQUEST_ID, successBody(JOB_ID, REQUEST_ID)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("없는 작업도 404 다 — jobId 를 훑을 단서를 주지 않는다")
    void unknownJobIsNotFound() throws Exception {
        mockMvc.perform(withToken(callback(99_999_301L, REQUEST_ID, successBody(99_999_301L, REQUEST_ID))))
                .andExpect(status().isNotFound());
    }

    // ── 계약 검증 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("헤더 X-Request-Id 와 본문 requestId 가 다르면 400")
    void requestIdMismatchIsRejected() throws Exception {
        mockMvc.perform(withToken(callback(JOB_ID, "헤더쪽", successBody(JOB_ID, "본문쪽"))))
                .andExpect(status().isBadRequest());

        assertThat(jobStatus(JOB_ID)).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("경로 jobId 와 본문 jobId 가 다르면 400")
    void jobIdMismatchIsRejected() throws Exception {
        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, successBody(JOB_ID + 1, REQUEST_ID))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("requestId 가 없으면 400 — 멱등 키 없이는 중복을 막을 수 없다")
    void blankRequestIdIsRejected() throws Exception {
        String body = """
                {"requestId":"","jobId":%d,"estimable":true}
                """.formatted(JOB_ID);
        mockMvc.perform(withToken(callback(JOB_ID, "", body)))
                .andExpect(status().isBadRequest());
    }

    // ── 상태 전이 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("성공 callback 을 받으면 작업이 COMPLETED 가 되고 버전 정보가 남는다")
    void successMovesJobToCompleted() throws Exception {
        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, successBody(JOB_ID, REQUEST_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value((int) JOB_ID))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.duplicate").value(false));

        assertThat(jobStatus(JOB_ID)).isEqualTo("COMPLETED");
        assertThat(column("request_id")).isEqualTo(REQUEST_ID);
        assertThat(column("model_version")).isEqualTo("yolo-v8-20260901");
        assertThat(jdbcTemplate.queryForObject(
                "select pipeline_version_id from analysis_job where job_id = ?", Long.class, JOB_ID))
                .isEqualTo(3L);
        assertThat(jdbcTemplate.queryForObject(
                "select finished_at from analysis_job where job_id = ?", java.sql.Timestamp.class, JOB_ID))
                .isNotNull();
    }

    @Test
    @DisplayName("실패 callback 을 받으면 FAILED 가 되고 오류 코드가 남는다")
    void errorMovesJobToFailed() throws Exception {
        String body = """
                {
                  "requestId":"%s", "jobId":%d,
                  "modelVersion":"a307-ai-pipeline-20260912-v1", "pipelineVersionId":3,
                  "estimable":false, "nonEstimableReason":null,
                  "error":{"code":"MODEL_ERROR","message":"damage 모델을 실행할 수 없습니다","retryable":true}
                }
                """.formatted(REQUEST_ID, JOB_ID);

        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        assertThat(jobStatus(JOB_ID)).isEqualTo("FAILED");
        assertThat(column("failure_reason")).isEqualTo("MODEL_ERROR");
    }

    @Test
    @DisplayName("retry_count 를 올리지 않는다 — 실패 기록과 재시도는 다른 결정이다")
    void failureDoesNotBumpRetryCount() throws Exception {
        String body = """
                {"requestId":"%s","jobId":%d,"estimable":false,
                 "error":{"code":"INTERNAL","message":"x","retryable":false}}
                """.formatted(REQUEST_ID, JOB_ID);

        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, body)))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "select retry_count from analysis_job where job_id = ?", Integer.class, JOB_ID))
                .isZero();
    }

    // ── 멱등성 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 requestId 가 다시 오면 저장하지 않고 200 — AI 재시도 3회를 흡수한다")
    void duplicateCallbackIsIgnored() throws Exception {
        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, successBody(JOB_ID, REQUEST_ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(false));

        // AI 서버는 1초 → 5초 → 20초 로 최대 3회 재시도한다. 전부 같은 값으로 온다.
        for (int attempt = 0; attempt < 3; attempt++) {
            mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, successBody(JOB_ID, REQUEST_ID))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.duplicate").value(true));
        }

        assertThat(jobStatus(JOB_ID)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("다른 requestId 로 오면 409 — 철 지난 결과가 최신 견적을 덮지 않는다")
    void staleRequestIdIsRejected() throws Exception {
        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, successBody(JOB_ID, REQUEST_ID))))
                .andExpect(status().isOk());

        mockMvc.perform(withToken(callback(JOB_ID, "다른요청", successBody(JOB_ID, "다른요청"))))
                .andExpect(status().isConflict());

        assertThat(column("request_id")).isEqualTo(REQUEST_ID);
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    /** 계약 ⑥ 의 성공 본문. 저장은 다음 커밋이라 여기서는 구조가 통과하는지만 본다. */
    private String successBody(long jobId, String requestId) {
        return """
                {
                  "requestId":"%s", "jobId":%d,
                  "modelVersion":"yolo-v8-20260901", "pipelineVersionId":3,
                  "estimable":true, "nonEstimableReason":null, "confidenceGrade":"MEDIUM",
                  "totals":{"min":480000,"median":550000,"max":610000},
                  "refCaseTotal":30, "refYearFrom":2021, "refYearTo":2021,
                  "items":[{
                    "partCode":"REAR_BUMPER","damageType":"Scratched","confidence":0.9321,
                    "repairMethod":"coating","standardHq":1.8,"partCost":null,
                    "laborCost":250000,"paintMaterialCost":85500,"itemTotal":335500,
                    "detectionIds":["501:damage:damage-001"],
                    "refCaseCount":18,"referencedCaseIds":[121381,121414],
                    "costDistribution":{"p25":300000,"median":335500,"p75":380000},
                    "fallbackStage":"CAR_CLASS",
                    "repairMethodReason":{"candidates":["coating"],"reasonCode":"SINGLE"}
                  }],
                  "imageResults":[{
                    "imageId":501,"width":1600,"height":1200,"excluded":false,"exclusionReason":null,
                    "detections":[{
                      "detectionId":"501:damage:damage-001","partCode":"REAR_BUMPER",
                      "damageType":"Scratched","pairStatus":"PAIRED","searchability":"STRICT",
                      "confidence":{"part":0.9612,"damage":0.9321},
                      "geometry":{"coordinateSystem":"PIXEL_XY_TOP_LEFT","bboxFormat":"XYWH",
                                  "bbox":{"x":460,"y":628,"width":694,"height":282},
                                  "areaPx":195708,"areaRatio":0.10193}
                    }]
                  }]
                }
                """.formatted(requestId, jobId);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder callback(
            long jobId, String requestIdHeader, String body) {
        return post("/internal/analysis-jobs/{jobId}/result", jobId)
                .header(AnalysisCallbackController.REQUEST_ID_HEADER, requestIdHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withToken(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder) {
        return builder.header(AnalysisCallbackController.TOKEN_HEADER, TOKEN);
    }

    private String jobStatus(long jobId) {
        return jdbcTemplate.queryForObject(
                "select status from analysis_job where job_id = ?", String.class, jobId);
    }

    private String column(String name) {
        return jdbcTemplate.queryForObject(
                "select " + name + " from analysis_job where job_id = ?", String.class, JOB_ID);
    }
}
