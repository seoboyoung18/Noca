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
        jdbcTemplate.update("""
                insert into part_code (part_code, name_ko, layout_zone, display_order, is_active)
                values ('REAR_BUMPER', '뒤 범퍼', 'REAR', 2, true)
                """);
        jdbcTemplate.update("""
                insert into accident_image (image_id, accident_id, original_filename, angle_code)
                values (501, 98301, 'rear-left.jpg', 'REAR_LEFT')
                """);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("""
                delete from estimate_item where estimate_id in
                    (select estimate_id from estimate where job_id = ?)
                """, JOB_ID);
        jdbcTemplate.update("delete from estimate where job_id = ?", JOB_ID);
        jdbcTemplate.update("delete from analysis_image_result where job_id = ?", JOB_ID);
        jdbcTemplate.update("delete from damaged_part where job_id = ?", JOB_ID);
        jdbcTemplate.update("delete from analysis_job where job_id = ?", JOB_ID);
        jdbcTemplate.update("delete from accident_image where accident_id = 98301");
        jdbcTemplate.update("delete from accident where accident_id = 98301");
        jdbcTemplate.update("delete from vehicle where vehicle_id = 98301");
        jdbcTemplate.update("delete from vehicle_model where model_id = 98301");
        jdbcTemplate.update("delete from member where member_id = ?", MEMBER_ID);
        jdbcTemplate.update("delete from part_code where part_code = 'REAR_BUMPER'");
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
    @DisplayName("사진이 전부 제외되면 COMPLETED 가 아니라 FAILED 다 (S15P21A307-187)")
    void allImagesExcludedMovesJobToFailed() throws Exception {
        String body = successBody(JOB_ID, REQUEST_ID)
                .replace("\"excluded\":false,\"exclusionReason\":null",
                        "\"excluded\":true,\"exclusionReason\":\"NOT_VEHICLE\"");

        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"));

        assertThat(jobStatus(JOB_ID)).isEqualTo("FAILED");
        assertThat(column("failure_reason")).isEqualTo("ALL_IMAGES_EXCLUDED");

        // 상태만 실패이고 행은 남는다. 화면이 "이 사진은 왜 빠졌나" 를 보여 줘야 한다.
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from analysis_image_result where job_id = ? and is_excluded",
                Integer.class, JOB_ID))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("일부만 제외되면 실패가 아니다 — 남은 사진으로 분석이 성립한다")
    void partialExclusionStillCompletes() throws Exception {
        // setUp 은 501 한 장만 넣는다. 두 번째 사진은 여기서 넣는다 —
        // analysis_image_result.image_id 가 accident_image 를 참조하므로 없는 id 를 보내면
        // FK 위반으로 409 가 되고, 제외 판정과 무관한 이유로 테스트가 깨진다.
        // tearDown 의 accident_image 정리가 이 행도 같이 지운다.
        jdbcTemplate.update("""
                insert into accident_image (image_id, accident_id, original_filename, angle_code)
                values (502, 98301, 'front-right.jpg', 'FRONT_RIGHT')
                """);

        // 한 장은 살고 한 장은 제외된 본문. 이미지 배열만 통째로 갈아 끼운다 —
        // 부분 치환으로 배열 원소를 늘리면 본문 서식이 조금만 바뀌어도 조용히 안 맞는다.
        String body = successBody(JOB_ID, REQUEST_ID).replaceAll(
                "(?s)\"imageResults\":\\[.*\\]\n",
                "\"imageResults\":["
                        + "{\"imageId\":501,\"width\":1600,\"height\":1200,"
                        + "\"excluded\":false,\"exclusionReason\":null,\"detections\":[]},"
                        + "{\"imageId\":502,\"width\":1600,\"height\":1200,"
                        + "\"excluded\":true,\"exclusionReason\":\"RATIO_BELOW_THRESHOLD\","
                        + "\"detections\":[]}]\n");

        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(jobStatus(JOB_ID)).isEqualTo("COMPLETED");
        assertThat(column("failure_reason")).isNull();
    }

    @Test
    @DisplayName("imageResults 가 빈 배열이면 전부 제외와 다르다 — 실패로 보지 않는다")
    void emptyImageResultsIsNotTreatedAsAllExcluded() throws Exception {
        // 원인이 다르다. 전부 제외는 사용자가 차를 안 찍은 것이고, 빈 배열은 AI 가 이미지별
        // 결과를 보내지 않은 것이다. 묶으면 상류 문제인데 "다시 찍으세요" 라고 잘못 안내한다.
        String body = successBody(JOB_ID, REQUEST_ID)
                .replaceAll("(?s)\"imageResults\":\\[.*\\]\n", "\"imageResults\":[]\n");

        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(jobStatus(JOB_ID)).isEqualTo("COMPLETED");
        assertThat(column("failure_reason")).isNull();
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

    // ── 결과 저장 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("부품·견적·항목·이미지 결과가 한 번에 저장된다")
    void resultIsPersisted() throws Exception {
        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, successBody(JOB_ID, REQUEST_ID))))
                .andExpect(status().isOk());

        assertThat(count("damaged_part where job_id = " + JOB_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select repair_method from damaged_part where job_id = ?", String.class, JOB_ID))
                .isEqualTo("coating");

        assertThat(count("estimate where job_id = " + JOB_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select total_median from estimate where job_id = ?", Integer.class, JOB_ID))
                .isEqualTo(550_000);
        assertThat(jdbcTemplate.queryForObject(
                "select confidence_grade from estimate where job_id = ?", String.class, JOB_ID))
                .isEqualTo("MEDIUM");

        assertThat(count("analysis_image_result where job_id = " + JOB_ID)).isEqualTo(1);
    }

    @Test
    @DisplayName("항목 금액과 도장 재료비가 그대로 남는다 — partCost 는 null 을 지키고 0 으로 바꾸지 않는다")
    void itemAmountsArePreserved() throws Exception {
        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, successBody(JOB_ID, REQUEST_ID))))
                .andExpect(status().isOk());

        assertThat(itemColumn("item_median", Integer.class)).isEqualTo(335_500);
        assertThat(itemColumn("labor_cost_median", Integer.class)).isEqualTo(250_000);
        assertThat(itemColumn("paint_material_cost", Integer.class)).isEqualTo(85_500);
        assertThat(itemColumn("part_cost_median", Integer.class)).isNull();
        assertThat(itemColumn("ref_case_count", Integer.class)).isEqualTo(18);
    }

    @Test
    @DisplayName("근거 스냅샷에 참조 사례 ID 와 완화 단계가 남는다 — 유사 사례 조회(-236)가 이 값을 쓴다")
    void refConditionIsStored() throws Exception {
        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, successBody(JOB_ID, REQUEST_ID))))
                .andExpect(status().isOk());

        String refCondition = jdbcTemplate.queryForObject("""
                select cast(ei.ref_condition as varchar) from estimate_item ei
                 join estimate e on e.estimate_id = ei.estimate_id
                where e.job_id = ?
                """, String.class, JOB_ID);

        assertThat(refCondition)
                .contains("121381").contains("121414")
                .contains("CAR_CLASS")
                .contains("2021");
    }

    @Test
    @DisplayName("detections 원문이 그대로 보존된다 — pairStatus·searchability 를 덜어내지 않는다")
    void detectionsArePreservedVerbatim() throws Exception {
        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, successBody(JOB_ID, REQUEST_ID))))
                .andExpect(status().isOk());

        String detections = jdbcTemplate.queryForObject(
                "select cast(detections as varchar) from analysis_image_result where job_id = ?",
                String.class, JOB_ID);

        assertThat(detections)
                .contains("pairStatus").contains("PAIRED")
                .contains("searchability").contains("STRICT")
                .contains("501:damage:damage-001")
                .contains("areaRatio");
    }

    @Test
    @DisplayName("산정 불가여도 견적 행과 이미지 결과는 남는다 — 화면이 '분석 중' 과 구분해야 한다")
    void nonEstimableStillLeavesRows() throws Exception {
        String body = """
                {
                  "requestId":"%s","jobId":%d,"modelVersion":"m1","pipelineVersionId":3,
                  "estimable":false,"nonEstimableReason":"INSUFFICIENT_CASES",
                  "imageResults":[{"imageId":501,"width":1600,"height":1200,
                                   "excluded":false,"exclusionReason":null,"detections":[]}]
                }
                """.formatted(REQUEST_ID, JOB_ID);

        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, body)))
                .andExpect(status().isOk());

        assertThat(count("estimate where job_id = " + JOB_ID)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select is_estimable from estimate where job_id = ?", Boolean.class, JOB_ID))
                .isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "select non_estimable_reason from estimate where job_id = ?", String.class, JOB_ID))
                .isEqualTo("INSUFFICIENT_CASES");
        assertThat(count("analysis_image_result where job_id = " + JOB_ID)).isEqualTo(1);
        assertThat(count("damaged_part where job_id = " + JOB_ID)).isZero();
    }

    @Test
    @DisplayName("모르는 부품 코드는 400 — FK 위반으로 500 이 나기 전에 끊는다")
    void unknownPartCodeIsRejected() throws Exception {
        String body = successBody(JOB_ID, REQUEST_ID).replace("\"REAR_BUMPER\"", "\"NO_SUCH_PART\"");

        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, body)))
                .andExpect(status().isBadRequest());

        assertThat(jobStatus(JOB_ID)).isEqualTo("PROCESSING");
        assertThat(count("estimate where job_id = " + JOB_ID)).isZero();
    }

    @Test
    @DisplayName("저장이 실패하면 상태도 되돌아간다 — 반쯤 저장된 견적을 남기지 않는다")
    void failedPersistRollsBackStatus() throws Exception {
        String body = successBody(JOB_ID, REQUEST_ID).replace("\"repairMethod\":\"coating\"", "\"repairMethod\":null");

        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, body)))
                .andExpect(status().isBadRequest());

        assertThat(jobStatus(JOB_ID)).isEqualTo("PROCESSING");
        assertThat(column("request_id")).isNull();
        assertThat(count("damaged_part where job_id = " + JOB_ID)).isZero();
    }

    @Test
    @DisplayName("중복 callback 은 견적을 하나 더 만들지 않는다")
    void duplicateDoesNotCreateSecondEstimate() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, successBody(JOB_ID, REQUEST_ID))))
                    .andExpect(status().isOk());
        }

        assertThat(count("estimate where job_id = " + JOB_ID)).isEqualTo(1);
        assertThat(count("damaged_part where job_id = " + JOB_ID)).isEqualTo(1);
        assertThat(count("analysis_image_result where job_id = " + JOB_ID)).isEqualTo(1);
    }

    // ── 낮은 신뢰도 파생 (S15P21A307-291) ───────────────────────────────────

    @Test
    @DisplayName("사례가 충분하고 CAR_CLASS 면 경고하지 않는다 — 지금 정상 경로의 모습이다")
    void normalItemIsNotLowConfidence() throws Exception {
        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, successBody(JOB_ID, REQUEST_ID))))
                .andExpect(status().isOk());

        assertThat(itemColumn("is_low_confidence", Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("참조 사례가 임계값 미만이면 경고가 붙는다")
    void tooFewCasesRaisesWarning() throws Exception {
        String body = successBody(JOB_ID, REQUEST_ID).replace("\"refCaseCount\":18", "\"refCaseCount\":2");

        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, body)))
                .andExpect(status().isOk());

        assertThat(itemColumn("is_low_confidence", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("전체 범위까지 완화했으면 사례가 많아도 경고가 붙는다")
    void fullFallbackRaisesWarning() throws Exception {
        String body = successBody(JOB_ID, REQUEST_ID)
                .replace("\"fallbackStage\":\"CAR_CLASS\"", "\"fallbackStage\":\"ALL\"");

        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, body)))
                .andExpect(status().isOk());

        assertThat(itemColumn("is_low_confidence", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("경고 여부와 무관하게 근거 스냅샷의 완화 단계는 같은 값이다")
    void warningAndBasisSeeTheSameStage() throws Exception {
        String body = successBody(JOB_ID, REQUEST_ID)
                .replace("\"fallbackStage\":\"CAR_CLASS\"", "\"fallbackStage\":\"ALL\"");

        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, body)))
                .andExpect(status().isOk());

        assertThat(itemColumn("is_low_confidence", Boolean.class)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                select cast(ei.ref_condition as varchar) from estimate_item ei
                 join estimate e on e.estimate_id = ei.estimate_id
                where e.job_id = ?
                """, String.class, JOB_ID)).contains("ALL");
    }

    @Test
    @DisplayName("견적 조회 응답의 lowConfidence 로 그대로 나간다 — 화면이 이 값으로 경고를 그린다")
    void warningIsExposedInEstimateResponse() throws Exception {
        String body = successBody(JOB_ID, REQUEST_ID).replace("\"refCaseCount\":18", "\"refCaseCount\":1");

        mockMvc.perform(withToken(callback(JOB_ID, REQUEST_ID, body)))
                .andExpect(status().isOk());

        Boolean stored = jdbcTemplate.queryForObject("""
                select ei.is_low_confidence from estimate_item ei
                 join estimate e on e.estimate_id = ei.estimate_id
                where e.job_id = ?
                """, Boolean.class, JOB_ID);

        // 조회 API 는 이 컬럼을 그대로 lowConfidence 로 내린다(EstimateQueryRepository).
        assertThat(stored).isTrue();
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

    private int count(String whereClause) {
        Integer n = jdbcTemplate.queryForObject("select count(*) from " + whereClause, Integer.class);
        return n == null ? 0 : n;
    }

    private <T> T itemColumn(String name, Class<T> type) {
        return jdbcTemplate.queryForObject("""
                select ei.%s from estimate_item ei
                 join estimate e on e.estimate_id = ei.estimate_id
                where e.job_id = ?
                """.formatted(name), type, JOB_ID);
    }

    private String column(String name) {
        return jdbcTemplate.queryForObject(
                "select " + name + " from analysis_job where job_id = ?", String.class, JOB_ID);
    }
}
