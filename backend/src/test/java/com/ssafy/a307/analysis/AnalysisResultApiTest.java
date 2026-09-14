package com.ssafy.a307.analysis;

import com.ssafy.a307.common.security.CurrentMemberProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 분석 결과 조회 (S15P21A307-203).
 *
 * <p>이 테스트가 지키는 것은 <b>좌표 원문 보존</b>과 <b>소유자 은닉</b>이다. 화면이 사진 위에
 * 부위를 그리는 데 필요한 값이 그대로 나가는지, 남의 사고가 새어 나가지 않는지를 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "USER")
@DisplayName("분석 결과 조회 (S15P21A307-203)")
class AnalysisResultApiTest {

    private static final long OWNER_ID = 98_401L;
    private static final long STRANGER_ID = 98_402L;
    private static final long ACCIDENT_ID = 98_401L;
    private static final long JOB_ID = 98_401L;
    private static final long IMAGE_ID = 98_401L;

    /** 계약 ⑥ 의 detections 원문. pairStatus·searchability 가 들어 있다. */
    private static final String DETECTIONS = """
            [{"detectionId":"98401:damage:damage-001","partCode":"REAR_BUMPER",
              "damageType":"Scratched","pairStatus":"PAIRED","searchability":"STRICT",
              "confidence":{"part":0.9612,"damage":0.9321},
              "geometry":{"coordinateSystem":"PIXEL_XY_TOP_LEFT","bboxFormat":"XYWH",
                          "bbox":{"x":460,"y":628,"width":694,"height":282},
                          "polygons":[[{"x":460,"y":628},{"x":1154,"y":910}]],
                          "areaPx":195708,"areaRatio":0.10193}}]
            """;

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;

    /**
     * 세션을 만들지 않고 회원 ID 만 고정한다. 진행 상태 테스트
     * ({@code AnalysisProgressControllerTest})와 같은 방식이다 — 이 테스트가 보려는 것은
     * 로그인 흐름이 아니라 <b>소유자 판정과 조립 결과</b>다.
     */
    @MockitoBean
    private CurrentMemberProvider currentMemberProvider;

    @BeforeEach
    void setUp() {
        insertMember(OWNER_ID, "result-owner", "결과주인");
        insertMember(STRANGER_ID, "result-stranger", "남");
        given(currentMemberProvider.currentMemberId()).willReturn(OWNER_ID);

        jdbcTemplate.update("""
                insert into vehicle_model (model_id, manufacturer, model_name, vehicle_type, car_class)
                values (98401, '현대', '결과테스트차', 'SEDAN', 'Mid-size')
                """);
        jdbcTemplate.update("""
                insert into vehicle (vehicle_id, member_id, model_id, model_year)
                values (98401, ?, 98401, 2021)
                """, OWNER_ID);
        jdbcTemplate.update("""
                insert into accident (accident_id, vehicle_id, vehicle_input_type, snapshot_model_id,
                                      snapshot_manufacturer, snapshot_model_name, snapshot_vehicle_type,
                                      snapshot_car_class, snapshot_model_year)
                values (?, 98401, 'REGISTERED', 98401, '현대', '결과테스트차', 'SEDAN', 'Mid-size', 2021)
                """, ACCIDENT_ID);
        jdbcTemplate.update("""
                insert into part_code (part_code, name_ko, layout_zone, display_order, is_active)
                values ('REAR_BUMPER', '뒤 범퍼', 'REAR', 2, true)
                """);
        jdbcTemplate.update("""
                insert into accident_image (image_id, accident_id, original_filename, angle_code)
                values (?, ?, 'rear-left.jpg', 'REAR_LEFT')
                """, IMAGE_ID, ACCIDENT_ID);
        jdbcTemplate.update("""
                insert into accident_image_asset (image_id, variant, s3_key, width, height)
                values (?, 'ORIGINAL', 'accidents/98401/images/98401/original.jpg', 1600, 1200)
                """, IMAGE_ID);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("delete from analysis_image_result where job_id = ?", JOB_ID);
        jdbcTemplate.update("delete from damaged_part where job_id = ?", JOB_ID);
        jdbcTemplate.update("delete from analysis_job where job_id = ?", JOB_ID);
        jdbcTemplate.update("delete from accident_image_asset where image_id = ?", IMAGE_ID);
        jdbcTemplate.update("delete from accident_image where image_id = ?", IMAGE_ID);
        jdbcTemplate.update("delete from accident where accident_id = ?", ACCIDENT_ID);
        jdbcTemplate.update("delete from vehicle where vehicle_id = 98401");
        jdbcTemplate.update("delete from vehicle_model where model_id = 98401");
        jdbcTemplate.update("delete from part_code where part_code = 'REAR_BUMPER'");
        jdbcTemplate.update("delete from member where member_id in (?, ?)", OWNER_ID, STRANGER_ID);
    }

    // ── 은닉 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("남의 사고는 403 이 아니라 404 다 — 그 사고가 있다는 사실을 알려주지 않는다")
    void strangerGets404() throws Exception {
        givenCompletedAnalysis();

        given(currentMemberProvider.currentMemberId()).willReturn(STRANGER_ID);

        mockMvc.perform(get("/api/accidents/{id}/analysis/result", ACCIDENT_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("없는 사고도 같은 404 다")
    void unknownAccidentGets404() throws Exception {
        mockMvc.perform(get("/api/accidents/{id}/analysis/result", 99_999_401L))
                .andExpect(status().isNotFound());
    }

    // ── 빈 상태 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("분석을 아직 요청하지 않은 사고는 빈 상태 200 이다 — '없는 사고' 와 구분돼야 한다")
    void notRequestedIsEmpty200() throws Exception {
        mockMvc.perform(get("/api/accidents/{id}/analysis/result", ACCIDENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").doesNotExist())
                .andExpect(jsonPath("$.data.status").doesNotExist())
                .andExpect(jsonPath("$.data.parts.length()").value(0))
                .andExpect(jsonPath("$.data.images.length()").value(0));
    }

    // ── 결과 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("검출된 부위를 한글 표시명과 함께 준다 — FE 가 코드 32종을 하드코딩하지 않게")
    void partsAreReturnedWithDisplayName() throws Exception {
        givenCompletedAnalysis();

        mockMvc.perform(get("/api/accidents/{id}/analysis/result", ACCIDENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value((int) JOB_ID))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.parts.length()").value(1))
                .andExpect(jsonPath("$.data.parts[0].partCode").value("REAR_BUMPER"))
                .andExpect(jsonPath("$.data.parts[0].partNameKo").value("뒤 범퍼"))
                .andExpect(jsonPath("$.data.parts[0].layoutZone").value("REAR"))
                .andExpect(jsonPath("$.data.parts[0].damageType").value("Scratched"))
                .andExpect(jsonPath("$.data.parts[0].repairMethod").value("coating"))
                .andExpect(jsonPath("$.data.parts[0].repairMethodDisplayName").value("도장"));
    }

    @Test
    @DisplayName("수리 방식이 미정이면 표시명도 null 이다 — '미정' 같은 한글을 지어내지 않는다")
    void undeterminedMethodHasNoDisplayName() throws Exception {
        givenCompletedAnalysis();
        jdbcTemplate.update("update damaged_part set repair_method = null where job_id = ?", JOB_ID);

        mockMvc.perform(get("/api/accidents/{id}/analysis/result", ACCIDENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parts[0].repairMethod").doesNotExist())
                .andExpect(jsonPath("$.data.parts[0].repairMethodDisplayName").doesNotExist());
    }

    @Test
    @DisplayName("좌표 원문이 그대로 나간다 — pairStatus·searchability·polygons 를 덜어내지 않는다")
    void detectionsArePassedThroughVerbatim() throws Exception {
        givenCompletedAnalysis();

        mockMvc.perform(get("/api/accidents/{id}/analysis/result", ACCIDENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.images.length()").value(1))
                .andExpect(jsonPath("$.data.images[0].imageId").value((int) IMAGE_ID))
                .andExpect(jsonPath("$.data.images[0].angleCode").value("REAR_LEFT"))
                .andExpect(jsonPath("$.data.images[0].detections[0].pairStatus").value("PAIRED"))
                .andExpect(jsonPath("$.data.images[0].detections[0].searchability").value("STRICT"))
                .andExpect(jsonPath("$.data.images[0].detections[0].detectionId")
                        .value("98401:damage:damage-001"))
                .andExpect(jsonPath("$.data.images[0].detections[0].geometry.bbox.width").value(694))
                .andExpect(jsonPath("$.data.images[0].detections[0].geometry.polygons[0][0].x").value(460))
                .andExpect(jsonPath("$.data.images[0].detections[0].geometry.areaRatio").value(0.10193));
    }

    @Test
    @DisplayName("좌표 환산 기준인 원본 크기를 함께 준다")
    void originalSizeIsReturned() throws Exception {
        givenCompletedAnalysis();

        mockMvc.perform(get("/api/accidents/{id}/analysis/result", ACCIDENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.images[0].width").value(1600))
                .andExpect(jsonPath("$.data.images[0].height").value(1200));
    }

    @Test
    @DisplayName("원본 크기를 얻지 못한 사진은 크기가 null 이다 — 지어내지 않는다")
    void missingOriginalSizeIsNull() throws Exception {
        givenCompletedAnalysis();
        jdbcTemplate.update("""
                update accident_image_asset set width = null, height = null
                 where image_id = ? and variant = 'ORIGINAL'
                """, IMAGE_ID);

        mockMvc.perform(get("/api/accidents/{id}/analysis/result", ACCIDENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.images[0].width").doesNotExist());
    }

    @Test
    @DisplayName("제외된 사진도 사유와 함께 남긴다 — 다시 찍을지 사용자가 판단해야 한다")
    void excludedImageIsKept() throws Exception {
        givenCompletedAnalysis();
        jdbcTemplate.update("""
                update analysis_image_result
                   set is_excluded = true, exclusion_reason = 'NOT_VEHICLE', detections = null
                 where job_id = ?
                """, JOB_ID);

        mockMvc.perform(get("/api/accidents/{id}/analysis/result", ACCIDENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.images.length()").value(1))
                .andExpect(jsonPath("$.data.images[0].excluded").value(true))
                .andExpect(jsonPath("$.data.images[0].exclusionReason").value("NOT_VEHICLE"))
                .andExpect(jsonPath("$.data.images[0].detections").doesNotExist());
    }

    @Test
    @DisplayName("실패한 작업도 사유와 함께 조회된다 — 화면이 재시도를 안내해야 한다")
    void failedJobIsReturned() throws Exception {
        jdbcTemplate.update("""
                insert into analysis_job (job_id, accident_id, status, failure_reason)
                values (?, ?, 'FAILED', 'MODEL_ERROR')
                """, JOB_ID, ACCIDENT_ID);

        mockMvc.perform(get("/api/accidents/{id}/analysis/result", ACCIDENT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value("MODEL_ERROR"))
                .andExpect(jsonPath("$.data.parts.length()").value(0));
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private void givenCompletedAnalysis() {
        jdbcTemplate.update("""
                insert into analysis_job (job_id, accident_id, status)
                values (?, ?, 'COMPLETED')
                """, JOB_ID, ACCIDENT_ID);
        jdbcTemplate.update("""
                insert into damaged_part (job_id, part_code, damage_type, repair_method, confidence)
                values (?, 'REAR_BUMPER', 'Scratched', 'coating', 0.9321)
                """, JOB_ID);
        // H2 는 JSON 컬럼에 문자열을 넣을 때 FORMAT JSON 이 필요하다. 운영(PostgreSQL)에서는
        // 엔티티가 @JdbcTypeCode(SqlTypes.JSON) 으로 쓰므로 이 구문이 나오지 않는다 —
        // EstimateQueryApiTest 의 ref_condition 적재와 같은 사정이다.
        jdbcTemplate.update("""
                insert into analysis_image_result (job_id, image_id, detections, is_excluded)
                values (?, ?, ? FORMAT JSON, false)
                """, JOB_ID, IMAGE_ID, DETECTIONS);
    }

    private void insertMember(long memberId, String providerUserId, String nickname) {
        jdbcTemplate.update("""
                insert into member (member_id, provider, provider_user_id, nickname)
                values (?, 'KAKAO', ?, ?)
                """, memberId, providerUserId, nickname);
    }

}
