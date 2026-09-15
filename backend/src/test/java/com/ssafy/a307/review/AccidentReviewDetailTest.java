package com.ssafy.a307.review;

import com.ssafy.a307.accident.dto.ActualRepairCostRequest;
import com.ssafy.a307.accident.service.AccidentService;
import com.ssafy.a307.admin.AdminErrorCode;
import com.ssafy.a307.admin.AdminOperationException;
import com.ssafy.a307.admin.dto.AccidentReviewDecisionRequest;
import com.ssafy.a307.admin.dto.AccidentReviewDetailResponse;
import com.ssafy.a307.admin.service.AdminAccidentReviewService;
import com.ssafy.a307.auth.principal.UserPrincipal;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.repository.MemberRepository;
import com.ssafy.a307.review.entity.AccidentReviewStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 검수 비교 상세 조회 (S15P21A307-514).
 *
 * <p><b>빈 경우를 함께 본다.</b> 큐 적재 기준이 실제 수리비 입력이라(answer72 §2-1) 분석을 한
 * 번도 안 한 사고도 검수 대상이 된다 — 있는 경우만 테스트하면 절반만 한 것이다(prompt73 §5-2).
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는다. 서비스가 자기 트랜잭션에서 쓴 값을 다시
 * 읽어야 하기 때문이다. 높은 ID 대역을 쓰고 {@code @AfterEach} 에서 직접 지운다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.autoconfigure.exclude="
        + "org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration")
@DisplayName("검수 비교 상세 조회")
class AccidentReviewDetailTest {

    private static final long ADMIN_ID = 96_801L;
    private static final long OWNER_ID = 96_802L;
    private static final long MODEL_ID = 96_803L;
    private static final long VEHICLE_ID = 96_804L;
    private static final long ACCIDENT_FULL_ID = 96_805L;      // 분석·견적·체크리스트 모두 있음
    private static final long ACCIDENT_BARE_ID = 96_806L;      // 아무것도 없음
    private static final long JOB_OLD_ID = 96_811L;
    private static final long JOB_NEW_ID = 96_812L;
    private static final long ESTIMATE_OLD_ID = 96_821L;
    private static final long ESTIMATE_NEW_ID = 96_822L;
    private static final long CHECKLIST_ID = 96_831L;
    private static final String PART_CODE = "ZZ_REVIEW_PART";

    @Autowired private AccidentService accidentService;
    @Autowired private AdminAccidentReviewService adminService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private Authentication admin;
    private Authentication plainUser;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','review-detail-admin','검수관리자','ADMIN','ACTIVE')", ADMIN_ID);
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','review-detail-owner','차주','USER','ACTIVE')", OWNER_ID);
        jdbc.update("insert into vehicle_model(model_id,manufacturer,model_name,vehicle_type,car_class,is_active)"
                + " values(?,'현대','아반떼','SEDAN','Mid-size',true)", MODEL_ID);
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(?,?,?,2024)",
                VEHICLE_ID, OWNER_ID, MODEL_ID);
        jdbc.update("insert into part_code(part_code,name_ko,layout_zone,display_order,is_active)"
                + " values(?,'리어 도어','REAR',900,true)", PART_CODE);
        insertAccident(ACCIDENT_FULL_ID);
        insertAccident(ACCIDENT_BARE_ID);

        admin = authOf(ADMIN_ID);
        plainUser = authOf(OWNER_ID);
        SecurityContextHolder.getContext().setAuthentication(admin);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        cleanUp();
    }

    private Authentication authOf(long memberId) {
        Member member = memberRepository.findById(memberId).orElseThrow();
        UserPrincipal principal = UserPrincipal.ofMember(member);
        return new UsernamePasswordAuthenticationToken(principal, "n/a", principal.getAuthorities());
    }

    private void cleanUp() {
        jdbc.update("delete from audit_log where actor_member_id in (?,?)", ADMIN_ID, OWNER_ID);
        jdbc.update("delete from accident_review where accident_id in (?,?)",
                ACCIDENT_FULL_ID, ACCIDENT_BARE_ID);
        jdbc.update("delete from repair_checklist_item where checklist_id = ?", CHECKLIST_ID);
        jdbc.update("delete from repair_checklist where checklist_id = ?", CHECKLIST_ID);
        jdbc.update("delete from estimate where job_id in (?,?)", JOB_OLD_ID, JOB_NEW_ID);
        jdbc.update("delete from damaged_part where job_id in (?,?)", JOB_OLD_ID, JOB_NEW_ID);
        jdbc.update("delete from analysis_job where accident_id in (?,?)",
                ACCIDENT_FULL_ID, ACCIDENT_BARE_ID);
        jdbc.update("delete from accident where accident_id in (?,?)",
                ACCIDENT_FULL_ID, ACCIDENT_BARE_ID);
        jdbc.update("delete from vehicle where vehicle_id = ?", VEHICLE_ID);
        jdbc.update("delete from vehicle_model where model_id = ?", MODEL_ID);
        jdbc.update("delete from part_code where part_code = ?", PART_CODE);
        jdbc.update("delete from member where member_id in (?,?)", ADMIN_ID, OWNER_ID);
    }

    private void insertAccident(long accidentId) {
        jdbc.update("""
                insert into accident(
                    accident_id, vehicle_id, vehicle_input_type,
                    snapshot_model_id, snapshot_manufacturer, snapshot_model_name,
                    snapshot_vehicle_type, snapshot_car_class, snapshot_model_year)
                values(?, ?, 'REGISTERED', ?, '현대', '아반떼', 'SEDAN', 'Mid-size', 2024)
                """, accidentId, VEHICLE_ID, MODEL_ID);
    }

    /** 사용자가 실제 수리비를 넣는다 = 큐에 오르는 유일한 경로(answer72 §2-1). */
    private long queue(long accidentId, int cost) {
        accidentService.recordActualRepairCost(OWNER_ID, accidentId,
                new ActualRepairCostRequest(cost, LocalDate.now(), "서울정비소"));
        return jdbc.queryForObject("select review_id from accident_review where accident_id = ?",
                Long.class, accidentId);
    }

    private void insertJob(long jobId, long accidentId, String status) {
        jdbc.update("insert into analysis_job(job_id,accident_id,status) values(?,?,?)",
                jobId, accidentId, status);
    }

    private void insertDamagedPart(long jobId) {
        jdbc.update("insert into damaged_part(job_id,part_code,damage_type,repair_method,confidence)"
                + " values(?,?,'Scratched','exchange',0.9)", jobId, PART_CODE);
    }

    private void insertEstimate(long estimateId, long jobId, boolean estimable,
                                Integer min, Integer median, Integer max) {
        jdbc.update("insert into estimate(estimate_id,job_id,version,is_estimable,"
                        + "non_estimable_reason,total_min,total_median,total_max,confidence_grade)"
                        + " values(?,?,1,?,?,?,?,?,?)",
                estimateId, jobId, estimable, estimable ? null : "파손 부위를 찾지 못했습니다",
                min, median, max, estimable ? "HIGH" : null);
    }

    private void insertChecklist() {
        jdbc.update("insert into repair_checklist(checklist_id,accident_id,status,generation_no,"
                + "completed_at) values(?,?,'COMPLETED',1,current_timestamp)",
                CHECKLIST_ID, ACCIDENT_FULL_ID);
        jdbc.update("insert into repair_checklist_item(checklist_id,source,content,is_checked,"
                + "checked_at,display_order) values(?,'AI','AI 가 만든 항목',true,current_timestamp,1)",
                CHECKLIST_ID);
        jdbc.update("insert into repair_checklist_item(checklist_id,source,content,is_checked,"
                + "memo,display_order) values(?,'USER','내가 직접 넣은 항목',false,'정비사 답변',2)",
                CHECKLIST_ID);
    }

    // ─────────────────────────────────────────────────────────── 있는 경우

    @Nested
    @DisplayName("재료가 다 있는 건")
    class Full {

        @Test
        @DisplayName("목록 응답을 그대로 품고 그 위에 AI 산출물을 얹는다")
        void detailEmbedsListResponseAndAddsAiOutput() {
            insertJob(JOB_NEW_ID, ACCIDENT_FULL_ID, "COMPLETED");
            insertDamagedPart(JOB_NEW_ID);
            insertEstimate(ESTIMATE_NEW_ID, JOB_NEW_ID, true, 1_000_000, 1_300_000, 1_600_000);
            insertChecklist();
            long reviewId = queue(ACCIDENT_FULL_ID, 1_200_000);

            AccidentReviewDetailResponse detail = adminService.findDetail(reviewId);

            // 목록이 주던 값은 review 안에 그대로 있다 — 두 벌로 정의하지 않았다.
            assertThat(detail.review().reviewId()).isEqualTo(reviewId);
            assertThat(detail.review().accidentId()).isEqualTo(ACCIDENT_FULL_ID);
            assertThat(detail.review().status()).isEqualTo(AccidentReviewStatus.PENDING);
            assertThat(detail.review().actualRepairCost()).isEqualTo(1_200_000);
            assertThat(detail.review().manufacturer()).isEqualTo("현대");

            assertThat(detail.analysisJobId()).isEqualTo(JOB_NEW_ID);
            assertThat(detail.damagedParts()).hasSize(1);
            assertThat(detail.checklistItems()).hasSize(2);
        }

        @Test
        @DisplayName("총액 셋을 그대로 주고 구간 포함 여부만 계산해 준다")
        void estimateCarriesAllThreeTotals() {
            insertJob(JOB_NEW_ID, ACCIDENT_FULL_ID, "COMPLETED");
            insertEstimate(ESTIMATE_NEW_ID, JOB_NEW_ID, true, 1_000_000, 1_300_000, 1_600_000);
            long reviewId = queue(ACCIDENT_FULL_ID, 1_200_000);

            AccidentReviewDetailResponse.Estimate estimate = adminService.findDetail(reviewId).estimate();

            assertThat(estimate.totalMin()).isEqualTo(1_000_000);
            assertThat(estimate.totalMedian()).isEqualTo(1_300_000);
            assertThat(estimate.totalMax()).isEqualTo(1_600_000);
            assertThat(estimate.confidenceGrade()).isEqualTo("HIGH");
            assertThat(estimate.actualWithinRange()).isTrue();
        }

        @Test
        @DisplayName("실제 금액이 추정 구간 밖이면 false 다")
        void actualOutsideRangeIsFalse() {
            insertJob(JOB_NEW_ID, ACCIDENT_FULL_ID, "COMPLETED");
            insertEstimate(ESTIMATE_NEW_ID, JOB_NEW_ID, true, 1_000_000, 1_300_000, 1_600_000);
            long reviewId = queue(ACCIDENT_FULL_ID, 3_000_000);

            assertThat(adminService.findDetail(reviewId).estimate().actualWithinRange()).isFalse();
        }

        @Test
        @DisplayName("부위 판정에 한글 표시명이 붙는다 — FE 가 코드를 하드코딩하지 않는다")
        void damagedPartCarriesKoreanName() {
            insertJob(JOB_NEW_ID, ACCIDENT_FULL_ID, "COMPLETED");
            insertDamagedPart(JOB_NEW_ID);
            long reviewId = queue(ACCIDENT_FULL_ID, 1_200_000);

            AccidentReviewDetailResponse.DamagedPart part =
                    adminService.findDetail(reviewId).damagedParts().getFirst();

            assertThat(part.partCode()).isEqualTo(PART_CODE);
            assertThat(part.partNameKo()).isEqualTo("리어 도어");
            assertThat(part.damageType()).isEqualTo("Scratched");
            assertThat(part.repairMethod()).isEqualTo("exchange");
        }

        @Test
        @DisplayName("체크리스트가 사용자 흔적을 그대로 보여 준다 — USER 항목·체크·메모")
        void checklistShowsUserTraces() {
            insertChecklist();
            long reviewId = queue(ACCIDENT_FULL_ID, 1_200_000);

            AccidentReviewDetailResponse detail = adminService.findDetail(reviewId);

            assertThat(detail.checklistStatus()).isEqualTo("COMPLETED");
            assertThat(detail.checklistItems()).extracting(
                            AccidentReviewDetailResponse.ChecklistItem::source)
                    .containsExactly("AI", "USER");
            assertThat(detail.checklistItems().getFirst().checked()).isTrue();
            assertThat(detail.checklistItems().getLast().memo()).isEqualTo("정비사 답변");
        }
    }

    // ─────────────────────────────────────────────────────────── 어느 분석분인가

    @Nested
    @DisplayName("어느 분석 실행분을 보여 주나 (§2-3)")
    class WhichJob {

        @Test
        @DisplayName("PENDING 이면 가장 최근 COMPLETED 를 쓴다")
        void pendingUsesLatestCompleted() {
            insertJob(JOB_OLD_ID, ACCIDENT_FULL_ID, "COMPLETED");
            insertJob(JOB_NEW_ID, ACCIDENT_FULL_ID, "COMPLETED");
            long reviewId = queue(ACCIDENT_FULL_ID, 1_200_000);

            assertThat(adminService.findDetail(reviewId).analysisJobId()).isEqualTo(JOB_NEW_ID);
        }

        @Test
        @DisplayName("실패한 분석은 고르지 않는다")
        void failedJobIsNotChosen() {
            insertJob(JOB_OLD_ID, ACCIDENT_FULL_ID, "COMPLETED");
            insertJob(JOB_NEW_ID, ACCIDENT_FULL_ID, "FAILED");
            long reviewId = queue(ACCIDENT_FULL_ID, 1_200_000);

            assertThat(adminService.findDetail(reviewId).analysisJobId()).isEqualTo(JOB_OLD_ID);
        }

        /**
         * 판정된 건은 <b>판정 당시 화면을 재현</b>해야 한다. 재분석이 돌아 더 최신 완료분이
         * 생겨도 기록된 것을 그대로 보여 준다.
         */
        @Test
        @DisplayName("판정된 건은 기록된 reviewed_job_id 를 그대로 쓴다 — 재분석이 돌아도")
        void decidedUsesRecordedJob() {
            insertJob(JOB_OLD_ID, ACCIDENT_FULL_ID, "COMPLETED");
            insertEstimate(ESTIMATE_OLD_ID, JOB_OLD_ID, true, 900_000, 1_000_000, 1_100_000);
            long reviewId = queue(ACCIDENT_FULL_ID, 1_000_000);
            adminService.decide(reviewId,
                    new AccidentReviewDecisionRequest(AccidentReviewStatus.APPROVED, null));

            // 판정 뒤에 재분석이 완료됐다.
            insertJob(JOB_NEW_ID, ACCIDENT_FULL_ID, "COMPLETED");
            insertEstimate(ESTIMATE_NEW_ID, JOB_NEW_ID, true, 5_000_000, 6_000_000, 7_000_000);

            AccidentReviewDetailResponse detail = adminService.findDetail(reviewId);

            assertThat(detail.analysisJobId()).isEqualTo(JOB_OLD_ID);
            assertThat(detail.review().reviewedJobId()).isEqualTo(JOB_OLD_ID);
            assertThat(detail.estimate().estimateId()).isEqualTo(ESTIMATE_OLD_ID);
        }
    }

    // ─────────────────────────────────────────────────────────── 빈 경우

    /**
     * prompt73 §5-2. <b>있는 경우만 테스트하면 절반만 한 것이다.</b>
     * 전부 200 이어야 하고 500 이 나면 안 된다 — 고지 문구 하나가 없어 리포트 전체가 500 이
     * 났던 일(2026-09-14)과 같은 실수를 하지 않는다.
     */
    @Nested
    @DisplayName("빈 경우 — 전부 200 이다")
    class Empty {

        @Test
        @DisplayName("분석이 한 번도 없는 사고")
        void accidentWithoutAnalysis() {
            long reviewId = queue(ACCIDENT_BARE_ID, 500_000);

            AccidentReviewDetailResponse detail = adminService.findDetail(reviewId);

            assertThat(detail.analysisJobId()).isNull();
            assertThat(detail.estimate()).isNull();
            assertThat(detail.damagedParts()).isEmpty();
            assertThat(detail.checklistItems()).isEmpty();
            assertThat(detail.checklistStatus()).isNull();
            assertThat(detail.review().actualRepairCost()).isEqualTo(500_000);
        }

        @Test
        @DisplayName("분석은 있는데 견적이 없는 건")
        void analysisWithoutEstimate() {
            insertJob(JOB_NEW_ID, ACCIDENT_FULL_ID, "COMPLETED");
            insertDamagedPart(JOB_NEW_ID);
            long reviewId = queue(ACCIDENT_FULL_ID, 1_200_000);

            AccidentReviewDetailResponse detail = adminService.findDetail(reviewId);

            assertThat(detail.analysisJobId()).isEqualTo(JOB_NEW_ID);
            assertThat(detail.estimate()).isNull();
            assertThat(detail.damagedParts()).hasSize(1);
        }

        @Test
        @DisplayName("산정 불가 견적 — 총액이 없고 사유가 있다")
        void nonEstimableEstimate() {
            insertJob(JOB_NEW_ID, ACCIDENT_FULL_ID, "COMPLETED");
            insertEstimate(ESTIMATE_NEW_ID, JOB_NEW_ID, false, null, null, null);
            long reviewId = queue(ACCIDENT_FULL_ID, 1_200_000);

            AccidentReviewDetailResponse.Estimate estimate = adminService.findDetail(reviewId).estimate();

            assertThat(estimate.estimable()).isFalse();
            assertThat(estimate.nonEstimableReason()).isEqualTo("파손 부위를 찾지 못했습니다");
            assertThat(estimate.totalMin()).isNull();
            assertThat(estimate.actualWithinRange()).isNull();
        }

        @Test
        @DisplayName("체크리스트가 없는 사고")
        void accidentWithoutChecklist() {
            insertJob(JOB_NEW_ID, ACCIDENT_FULL_ID, "COMPLETED");
            long reviewId = queue(ACCIDENT_FULL_ID, 1_200_000);

            AccidentReviewDetailResponse detail = adminService.findDetail(reviewId);

            assertThat(detail.checklistStatus()).isNull();
            assertThat(detail.checklistItems()).isEmpty();
        }

        @Test
        @DisplayName("없는 reviewId 는 404 다")
        void missingReviewIsNotFound() {
            assertThatThrownBy(() -> adminService.findDetail(99_999_999L))
                    .isInstanceOf(AdminOperationException.class)
                    .extracting(e -> ((AdminOperationException) e).code())
                    .isEqualTo(AdminErrorCode.ADMIN_TARGET_NOT_FOUND);
        }
    }

    // ─────────────────────────────────────────────────────────── HTTP · 권한

    @Nested
    @DisplayName("HTTP 계약과 권한")
    class Http {

        @Test
        @DisplayName("관리자는 200 이고 응답이 목록 값을 품는다")
        void adminGetsDetail() throws Exception {
            insertJob(JOB_NEW_ID, ACCIDENT_FULL_ID, "COMPLETED");
            insertEstimate(ESTIMATE_NEW_ID, JOB_NEW_ID, true, 1_000_000, 1_300_000, 1_600_000);
            long reviewId = queue(ACCIDENT_FULL_ID, 1_200_000);

            mockMvc.perform(get("/api/admin/accident-reviews/{id}", reviewId)
                            .with(authentication(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.review.reviewId").value(reviewId))
                    .andExpect(jsonPath("$.data.review.status").value("PENDING"))
                    .andExpect(jsonPath("$.data.analysisJobId").value(JOB_NEW_ID))
                    .andExpect(jsonPath("$.data.estimate.totalMedian").value(1_300_000))
                    .andExpect(jsonPath("$.data.estimate.actualWithinRange").value(true));
        }

        @Test
        @DisplayName("없는 reviewId 는 HTTP 404 다")
        void missingIsHttp404() throws Exception {
            mockMvc.perform(get("/api/admin/accident-reviews/{id}", 99_999_999L)
                            .with(authentication(admin)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("ADMIN 이 아니면 403 이다")
        void plainUserIsForbidden() throws Exception {
            long reviewId = queue(ACCIDENT_FULL_ID, 1_200_000);

            mockMvc.perform(get("/api/admin/accident-reviews/{id}", reviewId)
                            .with(authentication(plainUser)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("비로그인은 401 이다")
        void anonymousIsUnauthorized() throws Exception {
            long reviewId = queue(ACCIDENT_FULL_ID, 1_200_000);

            // 익명 주체를 명시한다. 주지 않으면 @BeforeEach 가 세운 관리자 컨텍스트가 요청에
            // 그대로 실려 "비로그인" 을 검사한다면서 관리자로 요청하게 된다 —
            // SecurityContextHolder.clearContext() 로는 TestSecurityContextHolder 가 남아 안 된다.
            mockMvc.perform(get("/api/admin/accident-reviews/{id}", reviewId).with(anonymous()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("조회는 감사 로그를 남기지 않는다 — 조회는 행위가 아니다")
        void readDoesNotAudit() {
            long reviewId = queue(ACCIDENT_FULL_ID, 1_200_000);

            adminService.findDetail(reviewId);

            Map<String, Object> counted = jdbc.queryForMap(
                    "select count(*) as c from audit_log where target_type = 'ACCIDENT_REVIEW'");
            assertThat(((Number) counted.get("c")).intValue()).isZero();
        }
    }
}
