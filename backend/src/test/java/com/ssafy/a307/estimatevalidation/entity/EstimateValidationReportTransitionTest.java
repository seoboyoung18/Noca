package com.ssafy.a307.estimatevalidation.entity;

import com.ssafy.a307.estimatevalidation.domain.ValidationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리포트 상태 전이. <b>실제 H2 로</b> 검증한다 — 정본 DDL 의 CHECK 세 개를 어기는지는
 * 엔티티 단위 테스트만으로는 알 수 없고, flush 해야 드러난다.
 *
 * <ul>
 *   <li>{@code ck_evr_done} — {@code completed_at} 은 COMPLETED·FAILED 일 때만</li>
 *   <li>{@code ck_evr_retry} — {@code retry_count} 는 0~3</li>
 *   <li>{@code ck_evr_status} — 상태는 넷뿐</li>
 * </ul>
 */
@SpringBootTest
@Transactional
@DisplayName("검증 리포트 상태 전이")
class EstimateValidationReportTransitionTest {

    private static final long MEMBER_ID = 96_001L;
    private static final long MODEL_ID = 96_002L;
    private static final long VEHICLE_ID = 96_003L;
    private static final long ACCIDENT_ID = 96_004L;

    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;

    private EstimateValidation validation;

    @BeforeEach
    void setUp() {
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','report-member','tester','USER','ACTIVE')", MEMBER_ID);
        jdbc.update("insert into vehicle_model(model_id,manufacturer,model_name,vehicle_type,car_class,is_active)"
                + " values(?,'현대','아반떼','SEDAN','Mid-size',true)", MODEL_ID);
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(?,?,?,2024)",
                VEHICLE_ID, MEMBER_ID, MODEL_ID);
        jdbc.update("""
                insert into accident(
                    accident_id, vehicle_id, vehicle_input_type,
                    snapshot_model_id, snapshot_manufacturer, snapshot_model_name,
                    snapshot_vehicle_type, snapshot_car_class, snapshot_model_year)
                values(?, ?, 'REGISTERED', ?, '현대', '아반떼', 'SEDAN', 'Mid-size', 2024)
                """, ACCIDENT_ID, VEHICLE_ID, MODEL_ID);

        com.ssafy.a307.accident.entity.Accident accident =
                entityManager.find(com.ssafy.a307.accident.entity.Accident.class, ACCIDENT_ID);
        validation = EstimateValidation.processingManual(MEMBER_ID, accident, null, 150_000);
        validation.initializeReport();
        entityManager.persist(validation);
        entityManager.flush();
    }

    private EstimateValidationReport report() {
        return validation.getReport();
    }

    /** DB 까지 내려보내 CHECK 위반이 없는지 확인한다. */
    private void flush() {
        entityManager.flush();
    }

    // ------------------------------------------------------------------ 정상 경로

    @Test
    @DisplayName("QUEUED → PROCESSING 은 completed_at 을 건드리지 않는다 (ck_evr_done)")
    void processingLeavesCompletedAtNull() {
        report().markProcessing();
        flush();

        assertThat(report().getStatus()).isEqualTo(ValidationStatus.PROCESSING);
        assertThat(report().getCompletedAt()).isNull();
        assertThat(report().getRetryCount()).isEqualTo((short) 1);
        assertThat(completedAtInDb()).isNull();
    }

    @Test
    @DisplayName("PROCESSING → COMPLETED 는 키와 완료 시각을 함께 채운다")
    void completedFillsKeyAndTimestamp() {
        Instant now = Instant.now();
        report().markProcessing();
        report().markCompleted("estimates/reports/2026/09/1-abc.pdf", now);
        flush();

        assertThat(report().getStatus()).isEqualTo(ValidationStatus.COMPLETED);
        assertThat(report().getS3KeyPdf()).isEqualTo("estimates/reports/2026/09/1-abc.pdf");
        assertThat(report().getCompletedAt()).isEqualTo(now);
        assertThat(completedAtInDb()).isNotNull();
    }

    @Test
    @DisplayName("PROCESSING → FAILED 는 사유와 완료 시각을 채운다")
    void failedFillsReasonAndTimestamp() {
        report().markProcessing();
        report().markFailed("PDF를 만들지 못했습니다.", Instant.now());
        flush();

        assertThat(report().getStatus()).isEqualTo(ValidationStatus.FAILED);
        assertThat(report().getFailureReason()).isEqualTo("PDF를 만들지 못했습니다.");
        assertThat(completedAtInDb()).isNotNull();
    }

    // ------------------------------------------------------------------ 되돌아가는 전이 거절

    @Nested
    @DisplayName("되돌아가는 전이를 거절한다")
    class RejectsBackwardTransitions {

        @Test
        @DisplayName("COMPLETED 에서 PROCESSING 으로 되돌아갈 수 없다")
        void completedCannotGoBackToProcessing() {
            report().markProcessing();
            report().markCompleted("estimates/reports/2026/09/1-abc.pdf", Instant.now());

            assertThatThrownBy(() -> report().markProcessing())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("QUEUED 를 바로 COMPLETED 로 만들 수 없다")
        void queuedCannotJumpToCompleted() {
            assertThatThrownBy(() -> report().markCompleted("k", Instant.now()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("QUEUED 를 바로 FAILED 로 만들 수 없다")
        void queuedCannotJumpToFailed() {
            assertThatThrownBy(() -> report().markFailed("사유", Instant.now()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("키 없이 완료로 만들 수 없다 — 내려줄 파일을 못 찾는 완료가 된다")
        void completedRequiresKey() {
            report().markProcessing();

            assertThatThrownBy(() -> report().markCompleted("  ", Instant.now()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------ 재시도 상한

    @Nested
    @DisplayName("재시도 상한 (ck_evr_retry 0~3)")
    class RetryLimit {

        @Test
        @DisplayName("세 번까지 큐를 오갈 수 있고 retry_count 가 3을 넘지 않는다")
        void retriesStopAtThree() {
            for (int attempt = 1; attempt <= EstimateValidationReport.MAX_RETRY_COUNT; attempt++) {
                report().markProcessing();
                flush();
                assertThat(report().getRetryCount()).isEqualTo((short) attempt);
                if (report().canRetry()) report().returnToQueue();
            }

            assertThat(report().getRetryCount()).isEqualTo(EstimateValidationReport.MAX_RETRY_COUNT);
            assertThat(report().canRetry()).isFalse();
            assertThat(retryCountInDb()).isLessThanOrEqualTo(EstimateValidationReport.MAX_RETRY_COUNT);
        }

        @Test
        @DisplayName("상한에 닿으면 큐로 되돌릴 수 없다 — 무한 반복을 막는다")
        void cannotReturnToQueueAtLimit() {
            exhaustRetries();

            assertThatThrownBy(() -> report().returnToQueue())
                    .isInstanceOf(IllegalStateException.class);
        }

        /**
         * <b>정상 흐름은 이 상태를 만들지 않는다.</b> 큐로 되돌리는 것은 상한 미만일 때만
         * 허용되므로, "QUEUED 인데 retry_count 가 상한" 은 오래된 데이터나 사람이 손댄
         * DB 에서만 나온다. 그런 행이 남으면 선점 쿼리가 영영 집지 못해 <b>큐가 비지 않으므로</b>
         * 방어가 필요하다 — 그래서 상태를 DB 로 직접 만들어 재현한다.
         */
        @Test
        @DisplayName("상한에 닿은 채 큐에 남은 건은 abandon 으로 종결한다 (방어)")
        void exhaustedQueuedIsAbandoned() {
            jdbc.update("update estimate_validation_report set status='QUEUED', retry_count=?"
                            + " where validation_id = ?",
                    EstimateValidationReport.MAX_RETRY_COUNT, validation.getValidationId());
            entityManager.clear();
            EstimateValidationReport stale = entityManager.find(
                    EstimateValidationReport.class, validation.getValidationId());

            assertThat(stale.canRetry()).isFalse();
            stale.abandon("PDF 생성이 반복 실패해 중단되었습니다.", Instant.now());
            entityManager.flush();

            assertThat(stale.getStatus()).isEqualTo(ValidationStatus.FAILED);
            assertThat(jdbc.queryForObject(
                    "select retry_count from estimate_validation_report where validation_id = ?",
                    Integer.class, validation.getValidationId()))
                    .isEqualTo((int) EstimateValidationReport.MAX_RETRY_COUNT);
            assertThat(jdbc.queryForObject(
                    "select completed_at from estimate_validation_report where validation_id = ?",
                    Object.class, validation.getValidationId())).isNotNull();
        }

        @Test
        @DisplayName("아직 재시도할 수 있는 건은 abandon 으로 끝내지 않는다")
        void abandonRejectsRetryableReport() {
            assertThatThrownBy(() -> report().abandon("사유", Instant.now()))
                    .isInstanceOf(IllegalStateException.class);
        }

        private void exhaustRetries() {
            for (int i = 0; i < EstimateValidationReport.MAX_RETRY_COUNT; i++) {
                report().markProcessing();
                if (report().canRetry()) report().returnToQueue();
            }
        }
    }

    // ------------------------------------------------------------------ 사유 절단

    @Test
    @DisplayName("실패 사유가 VARCHAR(200) 을 넘으면 자른다 — 자르지 않으면 저장이 깨진다")
    void failureReasonIsTruncated() {
        String tooLong = "가".repeat(300);
        report().markProcessing();
        report().markFailed(tooLong, Instant.now());
        flush();

        assertThat(report().getFailureReason())
                .hasSize(EstimateValidationReport.MAX_FAILURE_REASON_LENGTH);
        assertThat(failureReasonInDb().length())
                .isLessThanOrEqualTo(EstimateValidationReport.MAX_FAILURE_REASON_LENGTH);
    }

    // ------------------------------------------------------------------ helpers

    private Object completedAtInDb() {
        entityManager.flush();
        return jdbc.queryForObject(
                "select completed_at from estimate_validation_report where validation_id = ?",
                Object.class, validation.getValidationId());
    }

    private Integer retryCountInDb() {
        entityManager.flush();
        return jdbc.queryForObject(
                "select retry_count from estimate_validation_report where validation_id = ?",
                Integer.class, validation.getValidationId());
    }

    private String failureReasonInDb() {
        entityManager.flush();
        return jdbc.queryForObject(
                "select failure_reason from estimate_validation_report where validation_id = ?",
                String.class, validation.getValidationId());
    }
}
