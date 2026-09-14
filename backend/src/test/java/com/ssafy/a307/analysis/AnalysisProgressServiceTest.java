package com.ssafy.a307.analysis;

import com.ssafy.a307.accident.dto.AccidentCreateRequest;
import com.ssafy.a307.accident.service.AccidentService;
import com.ssafy.a307.analysis.dto.AnalysisProgressResponse;
import com.ssafy.a307.analysis.entity.AnalysisJobStatus;
import com.ssafy.a307.analysis.entity.AnalysisStageStatus;
import com.ssafy.a307.analysis.entity.AnalysisStageType;
import com.ssafy.a307.analysis.service.AnalysisProgressService;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.vehicle.dto.VehicleCreateRequest;
import com.ssafy.a307.vehicle.dto.VehicleResponse;
import com.ssafy.a307.vehicle.service.VehicleService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 분석 진행 상태 조회(화면 5 · 10).
 *
 * <p><b>단계 행을 JDBC 로 직접 넣는다.</b> 행을 만드는 운영 코드가 없기 때문이다 —
 * 단계를 진행시키는 것은 비동기 분석 파이프라인({@code S15P21A307-155}) 몫이고, 없는 기능을
 * 흉내 내는 더미 생성 코드를 두지 않았다({@code AnalysisStage} javadoc).
 */
@SpringBootTest
@Transactional
@DisplayName("분석 진행 상태 조회")
class AnalysisProgressServiceTest {

    private static final long ME = 1L;
    private static final long OTHER = 2L;

    @Autowired
    private AnalysisProgressService analysisProgressService;
    @Autowired
    private AccidentService accidentService;
    @Autowired
    private VehicleService vehicleService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    private long avante;

    @BeforeEach
    void setUp() {
        insertMember(ME, "kakao-me");
        insertMember(OTHER, "kakao-other");
        avante = insertModel("현대", "아반떼");
    }

    @Nested
    @DisplayName("단계 진척")
    class Stages {

        @Test
        @DisplayName("4단계를 진행 순서대로 주고 RUNNING 인 것이 현재 단계다")
        void ordersStagesAndPicksRunning() {
            long accidentId = openAccident(ME);
            long jobId = insertJob(accidentId, AnalysisJobStatus.PROCESSING);
            // 일부러 뒤죽박죽으로 넣는다 — 정렬이 삽입 순서에 기대고 있으면 여기서 드러난다.
            insertStage(jobId, AnalysisStageType.MATCH, AnalysisStageStatus.RUNNING);
            insertStage(jobId, AnalysisStageType.PREPROCESS, AnalysisStageStatus.DONE);
            insertStage(jobId, AnalysisStageType.ESTIMATE, AnalysisStageStatus.PENDING);
            insertStage(jobId, AnalysisStageType.DETECT, AnalysisStageStatus.DONE);
            flushAndClear();

            AnalysisProgressResponse progress = analysisProgressService.progress(ME, accidentId);

            assertThat(progress.jobId()).isEqualTo(jobId);
            assertThat(progress.status()).isEqualTo(AnalysisJobStatus.PROCESSING);
            assertThat(progress.stages()).extracting(AnalysisProgressResponse.StageProgress::stage)
                    .containsExactly(AnalysisStageType.PREPROCESS, AnalysisStageType.DETECT,
                            AnalysisStageType.MATCH, AnalysisStageType.ESTIMATE);
            assertThat(progress.currentStage()).isEqualTo(AnalysisStageType.MATCH);
        }

        @Test
        @DisplayName("화면의 3/4 — DONE 만 세고 RUNNING·PENDING 은 세지 않는다")
        void countsOnlyDoneStages() {
            long accidentId = openAccident(ME);
            long jobId = insertJob(accidentId, AnalysisJobStatus.PROCESSING);
            insertStage(jobId, AnalysisStageType.PREPROCESS, AnalysisStageStatus.DONE);
            insertStage(jobId, AnalysisStageType.DETECT, AnalysisStageStatus.DONE);
            insertStage(jobId, AnalysisStageType.MATCH, AnalysisStageStatus.DONE);
            insertStage(jobId, AnalysisStageType.ESTIMATE, AnalysisStageStatus.RUNNING);
            flushAndClear();

            AnalysisProgressResponse progress = analysisProgressService.progress(ME, accidentId);

            assertThat(progress.doneStages()).isEqualTo(3);
            assertThat(progress.totalStages()).isEqualTo(4);
        }

        @Test
        @DisplayName("FAILED 단계는 완료로 세지 않고 현재 단계도 아니다")
        void failedStageIsNeitherDoneNorCurrent() {
            long accidentId = openAccident(ME);
            long jobId = insertJob(accidentId, AnalysisJobStatus.FAILED);
            insertStage(jobId, AnalysisStageType.PREPROCESS, AnalysisStageStatus.DONE);
            insertStage(jobId, AnalysisStageType.DETECT, AnalysisStageStatus.FAILED);
            flushAndClear();

            AnalysisProgressResponse progress = analysisProgressService.progress(ME, accidentId);

            assertThat(progress.doneStages()).isEqualTo(1);
            assertThat(progress.currentStage()).isNull();
        }

        @Test
        @DisplayName("단계 행이 하나도 없으면 빈 배열 + doneStages 0 이다 — 작업은 있다")
        void jobWithoutStages() {
            long accidentId = openAccident(ME);
            long jobId = insertJob(accidentId, AnalysisJobStatus.QUEUED);
            flushAndClear();

            AnalysisProgressResponse progress = analysisProgressService.progress(ME, accidentId);

            assertThat(progress.jobId()).isEqualTo(jobId);
            assertThat(progress.stages()).isEmpty();
            assertThat(progress.doneStages()).isZero();
            assertThat(progress.totalStages()).isEqualTo(4);
            assertThat(progress.currentStage()).isNull();
        }

        @Test
        @DisplayName("detail 은 파이프라인이 넣은 값을 그대로 통과시킨다 — 서버가 만들지 않는다")
        void passesDetailThrough() {
            long accidentId = openAccident(ME);
            long jobId = insertJob(accidentId, AnalysisJobStatus.PROCESSING);
            insertStage(jobId, AnalysisStageType.MATCH, AnalysisStageStatus.RUNNING, "부품 12건 연결");
            flushAndClear();

            assertThat(analysisProgressService.progress(ME, accidentId).stages())
                    .singleElement()
                    .extracting(AnalysisProgressResponse.StageProgress::detail)
                    .isEqualTo("부품 12건 연결");
        }
    }

    @Nested
    @DisplayName("진행률은 주지 않는다")
    class NoPercentage {

        @Test
        @DisplayName("응답에 퍼센트·남은 시간 필드가 없다 — 단계 수만 준다")
        void exposesNoPercentField() {
            // 계약을 기록해 두는 테스트다. 필드를 추가하면 여기서 먼저 깨져,
            // 근거 없는 값을 내보내려는 변경이 조용히 지나가지 않는다.
            //
            // excludedImages 는 S15P21A307-186 에서 더했다. 이 목록에 넣어도 되는 이유는
            // 저장된 값(analysis_image_result.is_excluded · exclusion_reason)을 그대로
            // 전달할 뿐 서버가 만들어 낸 수치가 아니기 때문이다.
            // 퍼센트와 남은 시간은 여전히 없고, 앞으로도 없어야 한다.
            assertThat(AnalysisProgressResponse.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactly("jobId", "status", "failureReason", "startedAt", "finishedAt",
                            "totalStages", "doneStages", "currentStage", "stages", "excludedImages");
        }

        @Test
        @DisplayName("소요 시간을 서버가 계산하지 않고 시각 두 개를 그대로 준다")
        void exposesRawTimestamps() {
            long accidentId = openAccident(ME);
            long jobId = insertJob(accidentId, AnalysisJobStatus.COMPLETED);
            Instant started = Instant.parse("2026-09-05T01:00:00Z");
            Instant finished = Instant.parse("2026-09-05T01:00:27Z");
            setJobTimes(jobId, started, finished);
            flushAndClear();

            AnalysisProgressResponse progress = analysisProgressService.progress(ME, accidentId);

            assertThat(progress.startedAt()).isEqualTo(started);
            assertThat(progress.finishedAt()).isEqualTo(finished);
        }
    }

    @Nested
    @DisplayName("소유자·존재 판정")
    class Ownership {

        @Test
        @DisplayName("분석을 요청한 적 없는 사고는 오류가 아니라 빈 상태다")
        void notRequestedIsEmptyNotError() {
            long accidentId = openAccident(ME);
            flushAndClear();

            AnalysisProgressResponse progress = analysisProgressService.progress(ME, accidentId);

            assertThat(progress.jobId()).isNull();
            assertThat(progress.status()).isNull();
            assertThat(progress.stages()).isEmpty();
            assertThat(progress.doneStages()).isZero();
            assertThat(progress.totalStages()).isEqualTo(4);
        }

        @Test
        @DisplayName("남의 사고는 404 다 — 403 은 그 사고가 있다는 사실을 알려준다")
        void otherMembersAccidentIsNotFound() {
            long theirs = openAccident(OTHER);
            insertJob(theirs, AnalysisJobStatus.PROCESSING);
            flushAndClear();

            assertNotFound(() -> analysisProgressService.progress(ME, theirs));
        }

        @Test
        @DisplayName("없는 사고도 같은 404 다")
        void missingAccidentIsNotFound() {
            assertNotFound(() -> analysisProgressService.progress(ME, 999_999L));
        }
    }

    @Nested
    @DisplayName("재분석")
    class Reanalysis {

        @Test
        @DisplayName("작업이 여러 개면 가장 최근 것을 본다")
        void usesLatestJob() {
            long accidentId = openAccident(ME);
            long oldJob = insertJob(accidentId, AnalysisJobStatus.COMPLETED);
            backdateJob(oldJob, Instant.parse("2026-09-01T00:00:00Z"));
            long newJob = insertJob(accidentId, AnalysisJobStatus.PROCESSING);
            insertStage(newJob, AnalysisStageType.PREPROCESS, AnalysisStageStatus.RUNNING);
            flushAndClear();

            AnalysisProgressResponse progress = analysisProgressService.progress(ME, accidentId);

            assertThat(progress.jobId()).isEqualTo(newJob);
            assertThat(progress.status()).isEqualTo(AnalysisJobStatus.PROCESSING);
            assertThat(progress.currentStage()).isEqualTo(AnalysisStageType.PREPROCESS);
        }

        @Test
        @DisplayName("다른 작업의 단계가 섞여 들어오지 않는다")
        void doesNotMixStagesAcrossJobs() {
            long accidentId = openAccident(ME);
            long oldJob = insertJob(accidentId, AnalysisJobStatus.COMPLETED);
            backdateJob(oldJob, Instant.parse("2026-09-01T00:00:00Z"));
            insertStage(oldJob, AnalysisStageType.ESTIMATE, AnalysisStageStatus.DONE);
            long newJob = insertJob(accidentId, AnalysisJobStatus.PROCESSING);
            insertStage(newJob, AnalysisStageType.PREPROCESS, AnalysisStageStatus.DONE);
            flushAndClear();

            AnalysisProgressResponse progress = analysisProgressService.progress(ME, accidentId);

            assertThat(progress.stages()).extracting(AnalysisProgressResponse.StageProgress::stage)
                    .containsExactly(AnalysisStageType.PREPROCESS);
            assertThat(progress.doneStages()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("스키마")
    class Schema {

        @Test
        @DisplayName("uk_as — 같은 작업에 같은 단계를 두 번 넣을 수 없다")
        void stageIsUniquePerJob() {
            long accidentId = openAccident(ME);
            long jobId = insertJob(accidentId, AnalysisJobStatus.PROCESSING);
            insertStage(jobId, AnalysisStageType.MATCH, AnalysisStageStatus.RUNNING);

            assertThatThrownBy(
                    () -> insertStage(jobId, AnalysisStageType.MATCH, AnalysisStageStatus.DONE))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("ck_as_stage — 가이드에 없는 단계 코드는 거절된다")
        void rejectsUnknownStageCode() {
            long accidentId = openAccident(ME);
            long jobId = insertJob(accidentId, AnalysisJobStatus.PROCESSING);

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "insert into analysis_stage (job_id, stage, status) values (?, 'BLUR', 'PENDING')",
                    jobId))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }

    // ---------------------------------------------------------------- 픽스처

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .hasMessage("사고를 찾을 수 없습니다.")
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.NOT_FOUND);
    }

    private long openAccident(long memberId) {
        VehicleResponse vehicle = vehicleService.create(memberId, new VehicleCreateRequest(avante, 2020));
        long accidentId = accidentService
                .create(memberId, new AccidentCreateRequest(vehicle.vehicleId())).accidentId();
        flushAndClear();
        return accidentId;
    }

    private long insertJob(long accidentId, AnalysisJobStatus status) {
        jdbcTemplate.update("insert into analysis_job (accident_id, status) values (?, ?)",
                accidentId, status.name());
        return jdbcTemplate.queryForObject(
                "select max(job_id) from analysis_job where accident_id = ?", Long.class, accidentId);
    }

    private void insertStage(long jobId, AnalysisStageType stage, AnalysisStageStatus status) {
        insertStage(jobId, stage, status, null);
    }

    private void insertStage(long jobId, AnalysisStageType stage, AnalysisStageStatus status,
                             String detail) {
        jdbcTemplate.update(
                "insert into analysis_stage (job_id, stage, status, detail) values (?, ?, ?, ?)",
                jobId, stage.name(), status.name(), detail);
    }

    /** 최신 작업 판정이 created_at 을 먼저 보므로, 오래된 작업을 만들려면 시각을 뒤로 옮긴다. */
    private void backdateJob(long jobId, Instant createdAt) {
        jdbcTemplate.update("update analysis_job set created_at = ? where job_id = ?",
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), jobId);
    }

    private void setJobTimes(long jobId, Instant startedAt, Instant finishedAt) {
        jdbcTemplate.update(
                "update analysis_job set started_at = ?, finished_at = ? where job_id = ?",
                OffsetDateTime.ofInstant(startedAt, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(finishedAt, ZoneOffset.UTC),
                jobId);
    }

    private void insertMember(long memberId, String providerUserId) {
        jdbcTemplate.update(
                "insert into member (member_id, provider, provider_user_id, nickname)"
                        + " values (?, 'KAKAO', ?, ?)",
                memberId, providerUserId, "tester" + memberId);
    }

    private long insertModel(String manufacturer, String modelName) {
        jdbcTemplate.update(
                "insert into vehicle_model (manufacturer, model_name, vehicle_type, car_class, is_active)"
                        + " values (?, ?, 'SEDAN', 'Mid-size', true)",
                manufacturer, modelName);
        return jdbcTemplate.queryForObject(
                "select model_id from vehicle_model where manufacturer = ? and model_name = ?",
                Long.class, manufacturer, modelName);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
