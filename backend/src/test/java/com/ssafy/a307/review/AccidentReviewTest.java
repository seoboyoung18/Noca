package com.ssafy.a307.review;

import com.ssafy.a307.accident.dto.ActualRepairCostRequest;
import com.ssafy.a307.accident.service.AccidentService;
import com.ssafy.a307.admin.AdminOperationException;
import com.ssafy.a307.admin.AdminErrorCode;
import com.ssafy.a307.admin.dto.AccidentReviewDecisionRequest;
import com.ssafy.a307.admin.dto.AccidentReviewResponse;
import com.ssafy.a307.admin.dto.AdminPageResponse;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 검수 대기 큐 적재({@code S15P21A307-350})와 승인·반려({@code -352})를 <b>실제 H2</b> 로 본다.
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는다. 서비스가 자기 트랜잭션에서 쓴 값을 JDBC 로
 * 다시 읽어 확인해야 하기 때문이다. 대신 높은 ID 대역을 쓰고 {@code @AfterEach} 에서 직접 지운다.
 *
 * <p>actor 는 {@code CurrentMemberProvider} 가 세션에서 꺼내므로 {@link SecurityContextHolder} 에
 * 직접 넣는다 — {@code AdminMasterDataTest} 와 같은 방식이다.
 */
@SpringBootTest
@DisplayName("사고 데이터·피드백 검수")
class AccidentReviewTest {

    private static final long ADMIN_ID = 96_701L;
    private static final long OWNER_ID = 96_702L;
    private static final long MODEL_ID = 96_703L;
    private static final long VEHICLE_ID = 96_704L;
    private static final long ACCIDENT_ID = 96_705L;
    private static final long OTHER_ACCIDENT_ID = 96_706L;
    private static final long JOB_DONE_ID = 96_707L;
    private static final long JOB_FAILED_ID = 96_708L;

    @Autowired private AccidentService accidentService;
    @Autowired private AdminAccidentReviewService adminService;
    @Autowired private MemberRepository memberRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        cleanUp();
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','review-admin','검수관리자','ADMIN','ACTIVE')", ADMIN_ID);
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','review-owner','차주','USER','ACTIVE')", OWNER_ID);
        jdbc.update("insert into vehicle_model(model_id,manufacturer,model_name,vehicle_type,car_class,is_active)"
                + " values(?,'현대','아반떼','SEDAN','Mid-size',true)", MODEL_ID);
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(?,?,?,2024)",
                VEHICLE_ID, OWNER_ID, MODEL_ID);
        insertAccident(ACCIDENT_ID);
        insertAccident(OTHER_ACCIDENT_ID);

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
        jdbc.update("delete from accident_review where accident_id in (?,?)",
                ACCIDENT_ID, OTHER_ACCIDENT_ID);
        jdbc.update("delete from analysis_job where accident_id in (?,?)",
                ACCIDENT_ID, OTHER_ACCIDENT_ID);
        jdbc.update("delete from accident where accident_id in (?,?)", ACCIDENT_ID, OTHER_ACCIDENT_ID);
        jdbc.update("delete from vehicle where vehicle_id = ?", VEHICLE_ID);
        jdbc.update("delete from vehicle_model where model_id = ?", MODEL_ID);
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

    /** 사용자가 실제 수리비를 넣는다 = 큐에 오르는 유일한 경로. */
    private void recordCost(long accidentId, int cost) {
        accidentService.recordActualRepairCost(OWNER_ID, accidentId,
                new ActualRepairCostRequest(cost, LocalDate.now(), "서울정비소"));
    }

    // ─────────────────────────────────────────────────────────── 적재

    @Nested
    @DisplayName("검수 대기 큐 적재 (S15P21A307-350)")
    class Enqueue {

        @Test
        @DisplayName("실제 수리비를 입력하면 PENDING 으로 큐에 오른다")
        void costEntryEnqueues() {
            recordCost(ACCIDENT_ID, 1_200_000);

            Map<String, Object> row = reviewOf(ACCIDENT_ID);
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("queued_at")).isNotNull();
            assertThat(row.get("reviewed_at")).isNull();
            assertThat(row.get("reject_reason")).isNull();
            assertThat(row.get("snapshot_actual_repair_cost")).isNull();
            assertThat(row.get("reviewed_job_id")).isNull();
        }

        /**
         * prompt72 §7 의 고정. <b>예외가 아니라 아무 일도 일어나지 않아야</b> 한다 —
         * 사용자가 수리비를 정정하는 것은 정상 행동이고, 그때마다 500 이 나면 안 된다.
         */
        @Test
        @DisplayName("같은 사고를 두 번 적재해도 예외 없이 한 건만 남는다")
        void enqueueIsIdempotent() {
            recordCost(ACCIDENT_ID, 1_200_000);
            recordCost(ACCIDENT_ID, 1_350_000);
            recordCost(ACCIDENT_ID, 1_400_000);

            assertThat(countReviews(ACCIDENT_ID)).isEqualTo(1);
            // 사고 쪽 금액은 마지막 값으로 갱신된다 — 큐만 멱등하다.
            assertThat(jdbc.queryForObject(
                    "select actual_repair_cost from accident where accident_id = ?",
                    Integer.class, ACCIDENT_ID)).isEqualTo(1_400_000);
        }

        @Test
        @DisplayName("이미 판정된 사고는 다시 큐로 되돌리지 않는다")
        void decidedRowIsNotRequeued() {
            recordCost(ACCIDENT_ID, 1_200_000);
            adminService.decide(reviewId(ACCIDENT_ID), approve());

            recordCost(ACCIDENT_ID, 1_900_000);

            assertThat(countReviews(ACCIDENT_ID)).isEqualTo(1);
            assertThat(reviewOf(ACCIDENT_ID).get("status")).isEqualTo("APPROVED");
        }

        @Test
        @DisplayName("수리비를 넣지 않은 사고는 큐에 없다")
        void accidentWithoutCostIsNotQueued() {
            assertThat(countReviews(OTHER_ACCIDENT_ID)).isZero();
        }
    }

    // ─────────────────────────────────────────────────────────── 판정

    @Nested
    @DisplayName("승인·반려 (S15P21A307-352)")
    class Decide {

        @Test
        @DisplayName("승인하면 그 시점의 실제 수리비가 사본으로 복사된다")
        void approveCopiesCostSnapshot() {
            recordCost(ACCIDENT_ID, 1_200_000);

            AccidentReviewResponse after = adminService.decide(reviewId(ACCIDENT_ID), approve());

            assertThat(after.status()).isEqualTo(AccidentReviewStatus.APPROVED);
            assertThat(after.snapshotActualRepairCost()).isEqualTo(1_200_000);
            assertThat(after.reviewedAt()).isNotNull();
            assertThat(after.reviewerMemberId()).isEqualTo(ADMIN_ID);
            assertThat(reviewOf(ACCIDENT_ID).get("snapshot_actual_repair_cost")).isEqualTo(1_200_000);
        }

        @Test
        @DisplayName("승인 뒤 사용자가 금액을 고치면 사본은 그대로고 목록이 그 사실을 알려 준다")
        void snapshotDoesNotFollowLaterEdits() {
            recordCost(ACCIDENT_ID, 1_200_000);
            adminService.decide(reviewId(ACCIDENT_ID), approve());

            recordCost(ACCIDENT_ID, 2_000_000);

            AccidentReviewResponse row = firstOf(AccidentReviewStatus.APPROVED);
            assertThat(row.snapshotActualRepairCost()).isEqualTo(1_200_000);
            assertThat(row.actualRepairCost()).isEqualTo(2_000_000);
            assertThat(row.costChangedSinceReview()).isTrue();
        }

        @Test
        @DisplayName("판정하면 가장 최근 COMPLETED 분석이 reviewed_job_id 로 박힌다")
        void latestCompletedJobIsRecorded() {
            jdbc.update("insert into analysis_job(job_id,accident_id,status) values(?,?,'COMPLETED')",
                    JOB_DONE_ID, ACCIDENT_ID);
            jdbc.update("insert into analysis_job(job_id,accident_id,status) values(?,?,'FAILED')",
                    JOB_FAILED_ID, ACCIDENT_ID);
            recordCost(ACCIDENT_ID, 1_200_000);

            AccidentReviewResponse after = adminService.decide(reviewId(ACCIDENT_ID), approve());

            assertThat(after.reviewedJobId()).isEqualTo(JOB_DONE_ID);
        }

        @Test
        @DisplayName("분석이 없는 사고도 판정할 수 있다 — reviewed_job_id 는 null 이다")
        void accidentWithoutAnalysisCanBeDecided() {
            recordCost(ACCIDENT_ID, 900_000);

            AccidentReviewResponse after = adminService.decide(reviewId(ACCIDENT_ID), approve());

            assertThat(after.reviewedJobId()).isNull();
            assertThat(after.status()).isEqualTo(AccidentReviewStatus.APPROVED);
        }

        @Test
        @DisplayName("반려하면 사유가 남고 금액 사본은 비워진다")
        void rejectKeepsReasonAndClearsSnapshot() {
            recordCost(ACCIDENT_ID, 1_200_000);

            AccidentReviewResponse after = adminService.decide(reviewId(ACCIDENT_ID),
                    reject("사진이 흐려 부위를 확인할 수 없음"));

            assertThat(after.status()).isEqualTo(AccidentReviewStatus.REJECTED);
            assertThat(after.rejectReason()).isEqualTo("사진이 흐려 부위를 확인할 수 없음");
            assertThat(after.snapshotActualRepairCost()).isNull();
        }

        /**
         * {@code ck_ar_reject} 가 양방향이라 승인으로 뒤집을 때 사유가 남아 있으면 UPDATE 가
         * DB 에서 거부된다. 엔티티가 그 조합을 만들지 못하게 막는다.
         */
        @Test
        @DisplayName("반려를 승인으로 뒤집으면 반려 사유가 지워진다 — ck_ar_reject")
        void flippingRejectToApproveClearsReason() {
            recordCost(ACCIDENT_ID, 1_200_000);
            long id = reviewId(ACCIDENT_ID);
            adminService.decide(id, reject("일단 보류"));

            AccidentReviewResponse after = adminService.decide(id, approve());

            assertThat(after.status()).isEqualTo(AccidentReviewStatus.APPROVED);
            assertThat(after.rejectReason()).isNull();
            assertThat(reviewOf(ACCIDENT_ID).get("reject_reason")).isNull();
        }

        @Test
        @DisplayName("없는 검수 대상은 ADMIN_TARGET_NOT_FOUND 다")
        void missingReviewIsNotFound() {
            assertThatThrownBy(() -> adminService.decide(99_999_999L, approve()))
                    .isInstanceOf(AdminOperationException.class)
                    .extracting(e -> ((AdminOperationException) e).code())
                    .isEqualTo(AdminErrorCode.ADMIN_TARGET_NOT_FOUND);
        }

        @Test
        @DisplayName("승인·반려가 audit_log 에도 남는다")
        void decisionIsAudited() {
            recordCost(ACCIDENT_ID, 1_200_000);
            long id = reviewId(ACCIDENT_ID);

            adminService.decide(id, approve());
            adminService.decide(id, reject("역시 안 되겠다"));

            List<Map<String, Object>> logs = jdbc.queryForList(
                    "select action_type, target_type, target_id, change_reason, actor_member_id"
                            + " from audit_log where target_type = 'ACCIDENT_REVIEW' and target_id = ?"
                            + " order by audit_log_id", String.valueOf(id));

            assertThat(logs).hasSize(2);
            assertThat(logs).extracting(row -> row.get("action_type"))
                    .containsExactly("APPROVE", "REJECT");
            assertThat(logs).extracting(row -> row.get("actor_member_id"))
                    .allSatisfy(actor -> assertThat(((Number) actor).longValue()).isEqualTo(ADMIN_ID));
            assertThat(logs.get(0).get("change_reason")).isNull();
            assertThat(logs.get(1).get("change_reason")).isEqualTo("역시 안 되겠다");
        }
    }

    // ─────────────────────────────────────────────────────────── 목록

    @Nested
    @DisplayName("대기 목록 조회")
    class Search {

        @Test
        @DisplayName("status 로 거르고 오래된 순으로 준다")
        void listsPendingOldestFirst() {
            recordCost(ACCIDENT_ID, 1_200_000);
            recordCost(OTHER_ACCIDENT_ID, 800_000);

            AdminPageResponse<AccidentReviewResponse> pending =
                    adminService.search(AccidentReviewStatus.PENDING, oldestFirst());

            assertThat(pending.content()).extracting(AccidentReviewResponse::accidentId)
                    .containsExactly(ACCIDENT_ID, OTHER_ACCIDENT_ID);
            assertThat(pending.content()).extracting(AccidentReviewResponse::status)
                    .containsOnly(AccidentReviewStatus.PENDING);
        }

        @Test
        @DisplayName("판정한 건은 대기 목록에서 빠진다")
        void decidedRowLeavesTheQueue() {
            recordCost(ACCIDENT_ID, 1_200_000);
            recordCost(OTHER_ACCIDENT_ID, 800_000);
            adminService.decide(reviewId(ACCIDENT_ID), approve());

            assertThat(adminService.search(AccidentReviewStatus.PENDING, oldestFirst()).content())
                    .extracting(AccidentReviewResponse::accidentId)
                    .containsExactly(OTHER_ACCIDENT_ID);
            assertThat(adminService.search(AccidentReviewStatus.APPROVED, oldestFirst()).content())
                    .extracting(AccidentReviewResponse::accidentId)
                    .containsExactly(ACCIDENT_ID);
        }

        @Test
        @DisplayName("목록이 사고의 차량 스냅샷과 현재 금액을 함께 준다")
        void listCarriesAccidentContext() {
            recordCost(ACCIDENT_ID, 1_200_000);

            AccidentReviewResponse row = firstOf(AccidentReviewStatus.PENDING);

            assertThat(row.manufacturer()).isEqualTo("현대");
            assertThat(row.modelName()).isEqualTo("아반떼");
            assertThat(row.modelYear()).isEqualTo((short) 2024);
            assertThat(row.actualRepairCost()).isEqualTo(1_200_000);
            assertThat(row.costChangedSinceReview()).isFalse();
        }
    }

    // ─────────────────────────────────────────────────────────── DB 제약

    /**
     * prompt72 §5-2. <b>테스트가 통과한다고 제약이 지켜진 것이 아니다.</b> H2 에 직접 써서
     * CHECK 가 실제로 도는지 확인한다 — answer71 §5-3 이 PostgreSQL 에서 같은 방식으로 했다.
     */
    @Nested
    @DisplayName("CHECK 제약을 실제로 밟는다")
    class Constraints {

        @Test
        @DisplayName("반려인데 사유가 없으면 거부된다 — ck_ar_reject")
        void rejectWithoutReasonIsRejected() {
            assertThatThrownBy(() -> jdbc.update(
                    "insert into accident_review(accident_id,status,reviewed_at)"
                            + " values(?,'REJECTED',current_timestamp)", ACCIDENT_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("승인인데 반려 사유가 남아 있으면 거부된다 — ck_ar_reject 는 양방향이다")
        void approveWithReasonIsRejected() {
            assertThatThrownBy(() -> jdbc.update(
                    "insert into accident_review(accident_id,status,reviewed_at,reject_reason)"
                            + " values(?,'APPROVED',current_timestamp,'남아 있으면 안 된다')", ACCIDENT_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("PENDING 인데 판정 시각이 있으면 거부된다 — ck_ar_done")
        void pendingWithReviewedAtIsRejected() {
            assertThatThrownBy(() -> jdbc.update(
                    "insert into accident_review(accident_id,status,reviewed_at)"
                            + " values(?,'PENDING',current_timestamp)", ACCIDENT_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("판정했는데 시각이 없으면 거부된다 — ck_ar_done 도 양방향이다")
        void decidedWithoutReviewedAtIsRejected() {
            assertThatThrownBy(() -> jdbc.update(
                    "insert into accident_review(accident_id,status) values(?,'APPROVED')", ACCIDENT_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("어휘 밖 상태는 거부된다 — ck_ar_status")
        void unknownStatusIsRejected() {
            assertThatThrownBy(() -> jdbc.update(
                    "insert into accident_review(accident_id,status,reviewed_at)"
                            + " values(?,'IN_REVIEW',current_timestamp)", ACCIDENT_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("금액 0 은 거부된다 — ck_ar_cost")
        void zeroCostIsRejected() {
            assertThatThrownBy(() -> jdbc.update(
                    "insert into accident_review(accident_id,status,reviewed_at,"
                            + "snapshot_actual_repair_cost) values(?,'APPROVED',current_timestamp,0)",
                    ACCIDENT_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("같은 사고를 직접 두 번 넣으면 UNIQUE 가 막는다 — uk_ar_accident")
        void duplicateAccidentIsRejected() {
            jdbc.update("insert into accident_review(accident_id,status) values(?,'PENDING')",
                    ACCIDENT_ID);

            assertThatThrownBy(() -> jdbc.update(
                    "insert into accident_review(accident_id,status) values(?,'PENDING')", ACCIDENT_ID))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    // ─────────────────────────────────────────────────────────── 요청 검증

    /**
     * 불가능한 조합을 <b>400 으로 먼저</b> 막는다. 그대로 엔티티에 넘기면 {@code ck_ar_reject} 가
     * DB 에서 거부해 500 이 된다 — 잘못된 요청은 서버 오류가 아니다.
     *
     * <p>HTTP 계층까지 태우지 않고 검증 규칙 자체를 본다. {@code @Valid} 가 이 메서드를 부른다.
     */
    @Nested
    @DisplayName("판정 요청 검증")
    class RequestValidation {

        @Test
        @DisplayName("반려에 사유가 없으면 잘못된 요청이다")
        void rejectNeedsReason() {
            assertThat(new AccidentReviewDecisionRequest(AccidentReviewStatus.REJECTED, null)
                    .isReasonMatchesDecision()).isFalse();
            assertThat(new AccidentReviewDecisionRequest(AccidentReviewStatus.REJECTED, "   ")
                    .isReasonMatchesDecision()).isFalse();
        }

        @Test
        @DisplayName("승인에 사유를 보내면 잘못된 요청이다")
        void approveMustNotCarryReason() {
            assertThat(new AccidentReviewDecisionRequest(AccidentReviewStatus.APPROVED, "왜 여기 있나")
                    .isReasonMatchesDecision()).isFalse();
        }

        @Test
        @DisplayName("PENDING 으로 되돌리는 요청은 받지 않는다")
        void pendingIsNotADecision() {
            assertThat(new AccidentReviewDecisionRequest(AccidentReviewStatus.PENDING, null)
                    .isReasonMatchesDecision()).isFalse();
        }

        @Test
        @DisplayName("올바른 두 조합은 통과한다")
        void validCombinationsPass() {
            assertThat(new AccidentReviewDecisionRequest(AccidentReviewStatus.APPROVED, null)
                    .isReasonMatchesDecision()).isTrue();
            assertThat(new AccidentReviewDecisionRequest(AccidentReviewStatus.REJECTED, "사유")
                    .isReasonMatchesDecision()).isTrue();
        }
    }

    // ─────────────────────────────────────────────────────────── 도우미

    private static AccidentReviewDecisionRequest approve() {
        return new AccidentReviewDecisionRequest(AccidentReviewStatus.APPROVED, null);
    }

    private static AccidentReviewDecisionRequest reject(String reason) {
        return new AccidentReviewDecisionRequest(AccidentReviewStatus.REJECTED, reason);
    }

    private static PageRequest oldestFirst() {
        return PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "queuedAt"));
    }

    private AccidentReviewResponse firstOf(AccidentReviewStatus status) {
        return adminService.search(status, oldestFirst()).content().getFirst();
    }

    private Map<String, Object> reviewOf(long accidentId) {
        return jdbc.queryForMap("select * from accident_review where accident_id = ?", accidentId);
    }

    private long reviewId(long accidentId) {
        return jdbc.queryForObject("select review_id from accident_review where accident_id = ?",
                Long.class, accidentId);
    }

    private Integer countReviews(long accidentId) {
        return jdbc.queryForObject("select count(*) from accident_review where accident_id = ?",
                Integer.class, accidentId);
    }
}
