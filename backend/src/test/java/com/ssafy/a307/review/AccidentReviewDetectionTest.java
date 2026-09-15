package com.ssafy.a307.review;

import com.ssafy.a307.accident.dto.ActualRepairCostRequest;
import com.ssafy.a307.accident.service.AccidentService;
import com.ssafy.a307.admin.dto.AccidentReviewDetailResponse;
import com.ssafy.a307.admin.service.AdminAccidentReviewService;
import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.repository.MemberRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검수 상세가 <b>AI 가 실제로 준 것</b>을 보여 주는지 본다 (S15P21A307-514 후속 점검).
 *
 * <h2>왜 이 테스트가 있나</h2>
 *
 * <p>{@code damagedParts} 는 콜백의 {@code items[]} 로 만들어지는데 계약이 일부 검출을 그
 * 배열에서 뺀다 — {@code VECTOR_ONLY} 는 "확정 부품이 아니므로 포함하지 않습니다"
 * ({@code CallbackItem} javadoc), 부위를 못 찾은 검출은 {@code part_code NOT NULL} 이라 못 들어간다.
 * <b>그 검출들이 검수 화면에서 통째로 보이지 않았다.</b>
 *
 * <h2>🔴 키 이름의 정본은 콜백 계약 ⑥ 이다</h2>
 *
 * <p>{@code shared/vision/common_schema.json} 은 {@code detection_id}·{@code pair_status} 처럼
 * snake_case 이고 {@code part} 가 객체지만, <b>백엔드가 저장하는 것은 그 파일이 아니라 AI 서버가
 * 콜백으로 보낸 원문</b>이다({@code detectionId}·{@code partCode}·{@code pairStatus}).
 * 이 테스트의 고정 payload 가 {@code AnalysisCallbackApiTest} 와 같은 모양인 것이 그 근거다 —
 * 표기가 바뀌면 값이 조용히 {@code null} 이 되는 대신 여기서 깨진다.
 */
@SpringBootTest
@DisplayName("검수 상세의 검출 원문 반영")
class AccidentReviewDetectionTest {

    private static final long ADMIN_ID = 96_901L;
    private static final long OWNER_ID = 96_902L;
    private static final long MODEL_ID = 96_903L;
    private static final long VEHICLE_ID = 96_904L;
    private static final long ACCIDENT_ID = 96_905L;
    private static final long JOB_ID = 96_911L;
    private static final long IMAGE_A_ID = 96_921L;
    private static final long IMAGE_B_ID = 96_922L;

    /**
     * 계약 ⑥ 의 검출 원문. {@code AnalysisCallbackApiTest} 의 고정 payload 와 같은 표기다.
     *
     * <p>세 건을 담았다 — {@code STRICT}(부품 확정) · {@code VECTOR_ONLY}(부품은 있는데 모호) ·
     * {@code EXCLUDED}(부위를 못 찾음, {@code partCode} 없음). <b>뒤의 둘은 {@code items[]} 에
     * 오지 않아 {@code damaged_part} 에 없다.</b>
     */
    private static final String DETECTIONS_JSON = """
            [{"detectionId":"96911:damage:damage-001","partCode":"ZZ_DET_PART",
              "damageType":"Scratched","pairStatus":"PAIRED","searchability":"STRICT",
              "confidence":{"part":0.9612,"damage":0.9321}},
             {"detectionId":"96911:damage:damage-002","partCode":"ZZ_DET_PART",
              "damageType":"Crushed","pairStatus":"AMBIGUOUS","searchability":"VECTOR_ONLY",
              "confidence":{"part":0.4120,"damage":0.8800}},
             {"detectionId":"96911:damage:damage-003",
              "damageType":"Breakage","pairStatus":"UNPAIRED","searchability":"EXCLUDED",
              "confidence":{"part":null,"damage":0.3100}}]
            """;

    @Autowired private AccidentService accidentService;
    @Autowired private AdminAccidentReviewService adminService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','det-admin','검수관리자','ADMIN','ACTIVE')", ADMIN_ID);
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','det-owner','차주','USER','ACTIVE')", OWNER_ID);
        jdbc.update("insert into vehicle_model(model_id,manufacturer,model_name,vehicle_type,car_class,is_active)"
                + " values(?,'현대','아반떼','SEDAN','Mid-size',true)", MODEL_ID);
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(?,?,?,2024)",
                VEHICLE_ID, OWNER_ID, MODEL_ID);
        jdbc.update("""
                insert into accident(
                    accident_id, vehicle_id, vehicle_input_type,
                    snapshot_model_id, snapshot_manufacturer, snapshot_model_name,
                    snapshot_vehicle_type, snapshot_car_class, snapshot_model_year)
                values(?, ?, 'REGISTERED', ?, '현대', '아반떼', 'SEDAN', 'Mid-size', 2024)
                """, ACCIDENT_ID, VEHICLE_ID, MODEL_ID);
        jdbc.update("insert into part_code(part_code,name_ko,layout_zone,display_order,is_active)"
                + " values('ZZ_DET_PART','리어 도어','REAR',901,true)");
        jdbc.update("insert into analysis_job(job_id,accident_id,status) values(?,?,'COMPLETED')",
                JOB_ID, ACCIDENT_ID);

        Member admin = memberRepository.findById(ADMIN_ID).orElseThrow();
        UserPrincipal principal = UserPrincipal.ofMember(admin);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanUp();
    }

    private void cleanUp() {
        jdbc.update("delete from audit_log where actor_member_id in (?,?)", ADMIN_ID, OWNER_ID);
        jdbc.update("delete from accident_review where accident_id = ?", ACCIDENT_ID);
        jdbc.update("delete from analysis_image_result where job_id = ?", JOB_ID);
        jdbc.update("delete from accident_image_asset where image_id in (?,?)", IMAGE_A_ID, IMAGE_B_ID);
        jdbc.update("delete from accident_image where image_id in (?,?)", IMAGE_A_ID, IMAGE_B_ID);
        jdbc.update("delete from damaged_part where job_id = ?", JOB_ID);
        jdbc.update("delete from analysis_job where accident_id = ?", ACCIDENT_ID);
        jdbc.update("delete from accident where accident_id = ?", ACCIDENT_ID);
        jdbc.update("delete from vehicle where vehicle_id = ?", VEHICLE_ID);
        jdbc.update("delete from vehicle_model where model_id = ?", MODEL_ID);
        jdbc.update("delete from part_code where part_code = 'ZZ_DET_PART'");
        jdbc.update("delete from member where member_id in (?,?)", ADMIN_ID, OWNER_ID);
    }

    private void insertImage(long imageId, String angleCode) {
        jdbc.update("insert into accident_image(image_id,accident_id,original_filename,angle_code)"
                        + " values(?,?,?,?)",
                imageId, ACCIDENT_ID, "acc-" + imageId + ".jpg", angleCode);
    }

    private void insertImageResult(long imageId, boolean excluded, String reason, String detections) {
        jdbc.update("insert into analysis_image_result(job_id,image_id,is_excluded,exclusion_reason,"
                        + "detections) values(?,?,?,?,?" + " FORMAT JSON)",
                JOB_ID, imageId, excluded, reason, detections);
    }

    /** AI 가 items[] 로 보낸 것만 damaged_part 가 된다 — STRICT 한 건뿐이다. */
    private void insertDamagedPart() {
        jdbc.update("insert into damaged_part(job_id,part_code,damage_type,repair_method,confidence)"
                + " values(?,'ZZ_DET_PART','Scratched','exchange',0.9612)", JOB_ID);
    }

    private long queue() {
        accidentService.recordActualRepairCost(OWNER_ID, ACCIDENT_ID,
                new ActualRepairCostRequest(1_200_000, LocalDate.now(), "서울정비소"));
        return jdbc.queryForObject("select review_id from accident_review where accident_id = ?",
                Long.class, ACCIDENT_ID);
    }

    // ─────────────────────────────────────────────────────────── 본론

    @Nested
    @DisplayName("damagedParts 가 놓치던 검출을 보여 준다")
    class MissingDetections {

        @Test
        @DisplayName("damagedParts 는 한 건인데 검출은 셋이다 — 그 차이가 드러난다")
        void detectionsRevealWhatItemsDropped() {
            insertImage(IMAGE_A_ID, "FRONT");
            insertImageResult(IMAGE_A_ID, false, null, DETECTIONS_JSON);
            insertDamagedPart();
            long reviewId = queue();

            AccidentReviewDetailResponse detail = adminService.findDetail(reviewId);

            // 이것이 문제였다: 부위 판정은 한 줄뿐이다.
            assertThat(detail.damagedParts()).hasSize(1);
            // 이제 AI 가 실제로 본 셋이 보인다.
            assertThat(detail.detectionSummary().total()).isEqualTo(3);
            assertThat(detail.analysisImages()).singleElement()
                    .satisfies(image -> assertThat(image.detections()).hasSize(3));
        }

        @Test
        @DisplayName("searchability 분포를 센다 — STRICT·VECTOR_ONLY·EXCLUDED")
        void summaryCountsSearchability() {
            insertImage(IMAGE_A_ID, "FRONT");
            insertImageResult(IMAGE_A_ID, false, null, DETECTIONS_JSON);
            long reviewId = queue();

            AccidentReviewDetailResponse.DetectionSummary summary =
                    adminService.findDetail(reviewId).detectionSummary();

            assertThat(summary.total()).isEqualTo(3);
            assertThat(summary.strict()).isEqualTo(1);
            assertThat(summary.vectorOnly()).isEqualTo(1);
            assertThat(summary.excluded()).isEqualTo(1);
            assertThat(summary.withoutPart()).isEqualTo(1);
        }

        /**
         * 계약상 {@code part} 는 {@code null} 일 수 있는데 {@code damaged_part.part_code} 는
         * {@code NOT NULL} 이다. 그런 검출은 {@code damaged_part} 에 아예 없다.
         */
        @Test
        @DisplayName("부위를 못 찾은 검출도 보인다 — partCode 가 null 이다")
        void detectionWithoutPartIsVisible() {
            insertImage(IMAGE_A_ID, "FRONT");
            insertImageResult(IMAGE_A_ID, false, null, DETECTIONS_JSON);
            long reviewId = queue();

            List<AccidentReviewDetailResponse.Detection> detections =
                    adminService.findDetail(reviewId).analysisImages().getFirst().detections();

            assertThat(detections.getLast().partCode()).isNull();
            assertThat(detections.getLast().searchability()).isEqualTo("EXCLUDED");
            assertThat(detections.getLast().damageType()).isEqualTo("Breakage");
        }

        /**
         * {@code damaged_part.confidence} 는 "부품 단위 대표값" 하나다({@code CallbackItem} javadoc).
         * 계약의 두 값은 검출에만 있다 — 부위는 확실한데 손상 판정이 애매한 경우를 여기서 가른다.
         */
        @Test
        @DisplayName("confidence 두 값을 나눠 준다 — 부위 인식과 손상 인식")
        void confidenceIsSplitIntoTwo() {
            insertImage(IMAGE_A_ID, "FRONT");
            insertImageResult(IMAGE_A_ID, false, null, DETECTIONS_JSON);
            long reviewId = queue();

            List<AccidentReviewDetailResponse.Detection> detections =
                    adminService.findDetail(reviewId).analysisImages().getFirst().detections();

            assertThat(detections.getFirst().partConfidence()).isEqualByComparingTo("0.9612");
            assertThat(detections.getFirst().damageConfidence()).isEqualByComparingTo("0.9321");
            // 부위는 애매한데(0.41) 손상은 확실한(0.88) 검출 — 하나로 눌리면 구분되지 않는다.
            assertThat(detections.get(1).partConfidence()).isEqualByComparingTo("0.4120");
            assertThat(detections.get(1).damageConfidence()).isEqualByComparingTo("0.8800");
            // part 가 null 인 신뢰도도 그대로 null 이다 — 0 으로 바꾸지 않는다.
            assertThat(detections.getLast().partConfidence()).isNull();
        }

        @Test
        @DisplayName("pairStatus 세 값을 그대로 준다 — 한글 라벨로 바꾸지 않는다")
        void pairStatusIsACode() {
            insertImage(IMAGE_A_ID, "FRONT");
            insertImageResult(IMAGE_A_ID, false, null, DETECTIONS_JSON);
            long reviewId = queue();

            assertThat(adminService.findDetail(reviewId).analysisImages().getFirst().detections())
                    .extracting(AccidentReviewDetailResponse.Detection::pairStatus)
                    .containsExactly("PAIRED", "AMBIGUOUS", "UNPAIRED");
        }

        @Test
        @DisplayName("제외된 사진이 사유와 함께 보인다 — 학습 데이터로서 약하다는 신호다")
        void excludedImageIsVisible() {
            insertImage(IMAGE_A_ID, "FRONT");
            insertImageResult(IMAGE_A_ID, false, null, DETECTIONS_JSON);
            insertImage(IMAGE_B_ID, "REAR");
            insertImageResult(IMAGE_B_ID, true, "NOT_VEHICLE", "[]");
            long reviewId = queue();

            List<AccidentReviewDetailResponse.AnalysisImage> images =
                    adminService.findDetail(reviewId).analysisImages();

            assertThat(images).hasSize(2);
            assertThat(images.getLast().excluded()).isTrue();
            assertThat(images.getLast().exclusionReason()).isEqualTo("NOT_VEHICLE");
            assertThat(images.getLast().detections()).isEmpty();
        }
    }

    // ─────────────────────────────────────────────────────────── 빈 경우

    @Nested
    @DisplayName("빈 경우 — 500 을 내지 않는다")
    class Empty {

        @Test
        @DisplayName("detections 가 null 이어도 빈 목록이다")
        void nullDetectionsGiveEmptyList() {
            insertImage(IMAGE_A_ID, "FRONT");
            insertImageResult(IMAGE_A_ID, false, null, null);
            long reviewId = queue();

            AccidentReviewDetailResponse detail = adminService.findDetail(reviewId);

            assertThat(detail.analysisImages()).singleElement()
                    .satisfies(image -> assertThat(image.detections()).isEmpty());
            assertThat(detail.detectionSummary()).isEqualTo(
                    AccidentReviewDetailResponse.DetectionSummary.EMPTY);
        }

        @Test
        @DisplayName("손상이 없는 정상 사진은 빈 배열이다")
        void emptyArrayIsNormal() {
            insertImage(IMAGE_A_ID, "FRONT");
            insertImageResult(IMAGE_A_ID, false, null, "[]");
            long reviewId = queue();

            assertThat(adminService.findDetail(reviewId).detectionSummary().total()).isZero();
        }

        @Test
        @DisplayName("분석이 없는 사고는 사진도 요약도 비어 있다")
        void noAnalysisGivesEmpty() {
            jdbc.update("delete from analysis_job where job_id = ?", JOB_ID);
            long reviewId = queue();

            AccidentReviewDetailResponse detail = adminService.findDetail(reviewId);

            assertThat(detail.analysisJobId()).isNull();
            assertThat(detail.analysisImages()).isEmpty();
            assertThat(detail.detectionSummary()).isEqualTo(
                    AccidentReviewDetailResponse.DetectionSummary.EMPTY);
        }

        /**
         * {@code detections} 는 DDL 이 구조를 강제하지 않는 JSONB 다. 깨진 값 하나가 검수 화면
         * 전체를 죽이면 안 된다 — 그 사진의 검출만 비우고 나머지는 그대로 보여 준다.
         */
        @Test
        @DisplayName("원문이 배열이 아니어도 죽지 않는다 — 그 사진만 비운다")
        void nonArrayDetectionsDoNotBreakTheScreen() {
            insertImage(IMAGE_A_ID, "FRONT");
            insertImageResult(IMAGE_A_ID, false, null, """
                    {"unexpected": true}
                    """);
            insertImage(IMAGE_B_ID, "REAR");
            insertImageResult(IMAGE_B_ID, false, null, DETECTIONS_JSON);
            long reviewId = queue();

            AccidentReviewDetailResponse detail = adminService.findDetail(reviewId);

            assertThat(detail.analysisImages().getFirst().detections()).isEmpty();
            assertThat(detail.analysisImages().getLast().detections()).hasSize(3);
            assertThat(detail.detectionSummary().total()).isEqualTo(3);
        }
    }
}
