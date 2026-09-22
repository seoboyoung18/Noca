package com.ssafy.a307.analysis.request;

import com.ssafy.a307.accident.image.AccidentImageStoragePort;
import com.ssafy.a307.analysis.repository.AnalysisJobRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;

/**
 * 분석 요청 워커 한 주기 (S15P21A307-156). AI 서버와 사진 저장소는 대역이다.
 *
 * <p><b>트랜잭션을 걸지 않는다.</b> 처리기가 {@code REQUIRES_NEW} 로 커밋해야 워커가 다음 단계로
 * 가므로, 테스트 트랜잭션으로 감싸면 커밋이 보이지 않는다. 그래서 {@code @AfterEach} 로 직접 지운다
 * ({@code AnalysisCallbackApiTest} 와 같은 방식).
 *
 * <p>워커 빈은 테스트 설정에서 꺼져 있다. 여기서 직접 만들어 {@link AnalysisRequestWorker#pollOnce()}
 * 를 한 번씩 부른다 — 스케줄러가 임의로 돌면 다른 테스트가 흔들린다.
 */
@SpringBootTest
@DisplayName("분석 요청 워커 (S15P21A307-156)")
class AnalysisRequestWorkerTest {

    private static final long MEMBER = 97_801L;
    private static final long MODEL = 97_802L;
    private static final long VEHICLE = 97_803L;
    private static final long ACCIDENT = 97_804L;
    private static final long UPLOADED_IMAGE = 97_811L;
    private static final long RESERVED_IMAGE = 97_812L;
    private static final long JOB = 97_821L;
    private static final String RESIZED_KEY = "accidents/97804/images/97811/resized.jpg";
    private static final String ORIGINAL_KEY = "accidents/97804/images/97811/original.jpg";
    private static final String SELECTED_PART = "ZZ570_WORKER";

    @Autowired private AnalysisJobRepository jobRepository;
    @Autowired private AnalysisRequestProcessor processor;
    @Autowired private AnalysisRequestProperties properties;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private AccidentImageStoragePort storagePort;
    @MockitoBean private AiAnalysisClient aiClient;

    private AnalysisRequestWorker worker;

    @BeforeEach
    void setUp() {
        worker = new AnalysisRequestWorker(jobRepository, processor, aiClient, properties);

        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname) values(?,'KAKAO',?,?)",
                MEMBER, "analysis-worker", "워커");
        jdbc.update("insert into vehicle_model(model_id,manufacturer,model_name,vehicle_type,car_class,is_active)"
                + " values(?,'현대','아반떼','SEDAN','Compact',true)", MODEL);
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(?,?,?,2021)",
                VEHICLE, MEMBER, MODEL);
        jdbc.update("""
                insert into accident(accident_id,vehicle_id,vehicle_input_type,snapshot_model_id,
                                     snapshot_manufacturer,snapshot_model_name,snapshot_vehicle_type,
                                     snapshot_car_class,snapshot_model_year)
                values(?,?,'REGISTERED',?,'현대','아반떼','SEDAN','Compact',2021)
                """, ACCIDENT, VEHICLE, MODEL);

        given(storagePort.createPresignedDownloadUrl(any(), any())).willReturn(
                new AccidentImageStoragePort.PresignedDownload(
                        URI.create("https://s3.test/resized-97811.jpg"), Instant.parse("2026-09-15T10:10:00Z")));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from analysis_stage where job_id in (select job_id from analysis_job where accident_id=?)",
                ACCIDENT);
        jdbc.update("delete from analysis_job where accident_id=?", ACCIDENT);
        jdbc.update("delete from accident_image where accident_id=?", ACCIDENT);
        jdbc.update("delete from accident where accident_id=?", ACCIDENT);
        jdbc.update("delete from vehicle where vehicle_id=?", VEHICLE);
        jdbc.update("delete from vehicle_model where model_id=?", MODEL);
        jdbc.update("delete from member where member_id=?", MEMBER);
        // 작업이 참조하므로 작업을 지운 뒤에 지운다
        jdbc.update("delete from part_code where part_code=?", SELECTED_PART);
    }

    @Test
    @DisplayName("대기 작업을 PROCESSING 으로 옮기고 계약 모양으로 AI 에 보낸다 — 사진은 축소본만")
    void sendsQueuedJobWithResizedImages() {
        uploadedImage();
        reservedImage();
        queuedJob();

        worker.pollOnce();

        ArgumentCaptor<AnalysisRequestPayload> sent = ArgumentCaptor.forClass(AnalysisRequestPayload.class);
        then(aiClient).should().analyze(sent.capture());
        AnalysisRequestPayload payload = sent.getValue();

        Map<String, Object> job = job();
        assertThat(job.get("status")).isEqualTo("PROCESSING");
        assertThat(job.get("started_at")).isNotNull();
        assertThat(payload.jobId()).isEqualTo(JOB);
        assertThat(payload.requestId()).isEqualTo(job.get("request_id")).isNotBlank();
        assertThat(payload.vehicle()).isEqualTo(
                new AnalysisRequestPayload.Vehicle(MODEL, "현대", "아반떼", "Compact", 2021));
        // 업로드를 끝내지 않은 사진은 보내지 않는다.
        assertThat(payload.images()).containsExactly(new AnalysisRequestPayload.Image(
                UPLOADED_IMAGE, "FRONT", "https://s3.test/resized-97811.jpg", "2026-09-15T10:10:00Z"));
        assertThat(payload.callbackUrl())
                .isEqualTo("http://backend.test/internal/analysis-jobs/" + JOB + "/result");
        // 부위를 고르지 않은 작업이다 (S15P21A307-570)
        assertThat(payload.selectedPartCode()).isNull();

        then(storagePort).should().createPresignedDownloadUrl(eq(RESIZED_KEY), any());
        then(storagePort).should(never()).createPresignedDownloadUrl(eq(ORIGINAL_KEY), any());
    }

    /** 접수와 AI 호출 사이에 고른 부위를 들고 있는 곳은 작업뿐이다 (S15P21A307-570). */
    @Test
    @DisplayName("사용자가 고른 부위가 적힌 작업은 AI 요청에 그 부위를 싣는다")
    void sendsSelectedPartCode() {
        uploadedImage();
        queuedJob();
        jdbc.update("insert into part_code(part_code,name_ko,layout_zone,display_order,is_active,code_scope)"
                + " values(?,'시험 부위','SIDE_L',99,true,'AI_LABEL')", SELECTED_PART);
        jdbc.update("update analysis_job set selected_part_code=? where job_id=?", SELECTED_PART, JOB);

        worker.pollOnce();

        ArgumentCaptor<AnalysisRequestPayload> sent = ArgumentCaptor.forClass(AnalysisRequestPayload.class);
        then(aiClient).should().analyze(sent.capture());
        assertThat(sent.getValue().selectedPartCode()).isEqualTo(SELECTED_PART);
    }

    /**
     * <b>이 테스트가 순서를 지킨다.</b> AI 가 곧바로 결과를 보내도 결과 수신이 대조할 멱등 키가
     * 이미 DB 에 있어야 한다. AI 를 부르는 순간 DB 를 들여다본다.
     */
    @Test
    @DisplayName("AI 를 부르는 시점에 requestId 와 PROCESSING 이 이미 커밋돼 있다")
    void requestIdIsCommittedBeforeSending() {
        uploadedImage();
        queuedJob();
        AtomicReference<Map<String, Object>> seenByAi = new AtomicReference<>();
        willAnswer(invocation -> {
            seenByAi.set(job());
            return null;
        }).given(aiClient).analyze(any());

        worker.pollOnce();

        assertThat(seenByAi.get()).isNotNull();
        assertThat(seenByAi.get().get("status")).isEqualTo("PROCESSING");
        assertThat(seenByAi.get().get("request_id")).isNotNull();
    }

    @Test
    @DisplayName("AI 가 거절하면 그 코드로 FAILED 가 되고 진행 단계도 FAILED 로 적힌다")
    void aiRejectionFailsJob() {
        uploadedImage();
        queuedJob();
        willThrow(new AiAnalysisException("IMAGE_FETCH_FAILED", "만료", null))
                .given(aiClient).analyze(any());

        worker.pollOnce();

        Map<String, Object> job = job();
        assertThat(job.get("status")).isEqualTo("FAILED");
        assertThat(job.get("failure_reason")).isEqualTo("IMAGE_FETCH_FAILED");
        assertThat(job.get("finished_at")).isNotNull();
        assertThat(jdbc.queryForObject(
                "select count(*) from analysis_stage where job_id=? and status='FAILED'", Integer.class, JOB))
                .isEqualTo(4);
    }

    @Test
    @DisplayName("보낼 사진이 없어졌으면 AI 를 부르지 않고 NO_IMAGE 로 끝낸다")
    void noImageFailsWithoutCallingAi() {
        reservedImage();
        queuedJob();

        worker.pollOnce();

        then(aiClient).should(never()).analyze(any());
        assertThat(job().get("status")).isEqualTo("FAILED");
        assertThat(job().get("failure_reason")).isEqualTo(AnalysisRequestFailure.NO_IMAGE.name());
    }

    @Test
    @DisplayName("보낸 지 오래됐는데 결과가 없으면 ABANDONED 로 끝낸다")
    void staleProcessingIsAbandoned() {
        jdbc.update("insert into analysis_job(job_id,accident_id,status,request_id,started_at)"
                        + " values(?,?,'PROCESSING','old-request',?)",
                JOB, ACCIDENT, Timestamp.from(Instant.now().minus(11, ChronoUnit.MINUTES)));

        worker.pollOnce();

        assertThat(job().get("status")).isEqualTo("FAILED");
        assertThat(job().get("failure_reason")).isEqualTo(AnalysisRequestFailure.ABANDONED.name());
    }

    @Test
    @DisplayName("보낸 지 얼마 안 된 작업은 건드리지 않는다")
    void recentProcessingIsLeftAlone() {
        jdbc.update("insert into analysis_job(job_id,accident_id,status,request_id,started_at)"
                        + " values(?,?,'PROCESSING','recent-request',?)",
                JOB, ACCIDENT, Timestamp.from(Instant.now().minus(1, ChronoUnit.MINUTES)));

        worker.pollOnce();

        assertThat(job().get("status")).isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("결과가 먼저 도착해 끝난 작업은 실패 기록이 덮지 않는다")
    void finishedJobIsNotOverwritten() {
        jdbc.update("insert into analysis_job(job_id,accident_id,status,request_id,started_at,finished_at)"
                        + " values(?,?,'COMPLETED','done-request',?,?)",
                JOB, ACCIDENT, Timestamp.from(Instant.now()), Timestamp.from(Instant.now()));

        processor.markFailed(JOB, AnalysisRequestFailure.AI_UNREACHABLE.name());

        assertThat(job().get("status")).isEqualTo("COMPLETED");
        assertThat(job().get("failure_reason")).isNull();
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private void queuedJob() {
        jdbc.update("insert into analysis_job(job_id,accident_id,status) values(?,?,'QUEUED')", JOB, ACCIDENT);
    }

    private void uploadedImage() {
        jdbc.update("insert into accident_image(image_id,accident_id,original_filename,angle_code) values(?,?,?,?)",
                UPLOADED_IMAGE, ACCIDENT, "front.jpg", "FRONT");
        jdbc.update("insert into accident_image_asset(image_id,variant,s3_key) values(?,'ORIGINAL',?)",
                UPLOADED_IMAGE, ORIGINAL_KEY);
        jdbc.update("insert into accident_image_asset(image_id,variant,s3_key) values(?,'RESIZED',?)",
                UPLOADED_IMAGE, RESIZED_KEY);
    }

    /** 업로드 URL 만 받고 완료 통보가 없는 사진. asset 이 없다. */
    private void reservedImage() {
        jdbc.update("insert into accident_image(image_id,accident_id,original_filename) values(?,?,?)",
                RESERVED_IMAGE, ACCIDENT, "reserved.jpg");
    }

    private Map<String, Object> job() {
        return jdbc.queryForMap(
                "select status, request_id, failure_reason, started_at, finished_at from analysis_job where job_id=?",
                JOB);
    }
}
