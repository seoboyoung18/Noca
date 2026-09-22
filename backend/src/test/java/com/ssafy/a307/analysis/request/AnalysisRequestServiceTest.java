package com.ssafy.a307.analysis.request;

import com.ssafy.a307.accident.image.AccidentImageStoragePort;
import com.ssafy.a307.analysis.dto.AnalysisProgressResponse;
import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.entity.AnalysisJobStatus;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 분석 요청 접수 (S15P21A307-156). AI 는 부르지 않는다 — 접수는 {@code QUEUED} 를 만들 뿐이다.
 *
 * <p>저장소 어댑터는 대역이다. 테스트 설정에 버킷이 비어 있어 진짜 어댑터가 없으면 접수가 503 이라
 * 성공 경로를 볼 수 없다. 503 경로는 {@link AnalysisRequestAvailabilityTest} 가 따로 본다.
 */
@SpringBootTest
@Transactional
@DisplayName("분석 요청 접수 (S15P21A307-156)")
class AnalysisRequestServiceTest {

    private static final long ME = 97_601L;
    private static final long OTHER = 97_602L;
    private static final long MODEL_ID = 97_603L;
    private static final long MY_VEHICLE = 97_604L;
    private static final long OTHER_VEHICLE = 97_605L;
    private static final long MY_ACCIDENT = 97_606L;
    private static final long OTHER_ACCIDENT = 97_607L;
    private static final long MISSING_ACCIDENT = 97_999L;

    /** 부위 확정 재분석(S15P21A307-570)이 고르는 부위. 시드에 없는 이름이라 다른 테스트와 겹치지 않는다. */
    private static final String FENDER = "ZZ570_FENDER";
    private static final String EXTENDED = "ZZ570_EXT";
    private static final String INACTIVE = "ZZ570_OFF";

    /** 부품과 짝이 안 된 손상. 부품을 못 찾으면 AI 가 이렇게 보낸다. */
    private static final String UNPAIRED = """
            [{"detectionId":"1:damage:001","partCode":null,"damageType":"Scratched","pairStatus":"UNPAIRED"}]""";

    @Autowired private AnalysisRequestService requestService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private AccidentImageStoragePort storagePort;

    @BeforeEach
    void setUp() {
        insertMember(ME, "analysis-request-me");
        insertMember(OTHER, "analysis-request-other");
        jdbc.update("insert into vehicle_model(model_id,manufacturer,model_name,vehicle_type,car_class,is_active)"
                + " values(?,'현대','아반떼','SEDAN','Compact',true)", MODEL_ID);
        insertVehicle(MY_VEHICLE, ME);
        insertVehicle(OTHER_VEHICLE, OTHER);
        insertAccident(MY_ACCIDENT, MY_VEHICLE);
        insertAccident(OTHER_ACCIDENT, OTHER_VEHICLE);
    }

    @Nested
    @DisplayName("소유자 판정")
    class Ownership {

        @Test
        @DisplayName("없는 사고는 404 다")
        void missingAccidentIsNotFound() {
            assertError(() -> requestService.request(ME, MISSING_ACCIDENT), ErrorCode.NOT_FOUND);
        }

        @Test
        @DisplayName("남의 사고도 403 이 아니라 404 다")
        void otherMembersAccidentIsNotFound() {
            uploadedImage(97_701L, OTHER_ACCIDENT);

            assertError(() -> requestService.request(ME, OTHER_ACCIDENT), ErrorCode.NOT_FOUND);
            assertThat(jobCount(OTHER_ACCIDENT)).isZero();
        }
    }

    @Nested
    @DisplayName("사진 조건")
    class Images {

        @Test
        @DisplayName("사진이 한 장도 없으면 400 이고 작업을 만들지 않는다")
        void noImageIsBadRequest() {
            assertError(() -> requestService.request(ME, MY_ACCIDENT), ErrorCode.INVALID_REQUEST);
            assertThat(jobCount(MY_ACCIDENT)).isZero();
        }

        @Test
        @DisplayName("업로드 URL 만 받고 완료 통보가 없는 사진은 보낼 수 없어 400 이다")
        void reservedOnlyIsBadRequest() {
            jdbc.update("insert into accident_image(image_id,accident_id,original_filename) values(?,?,?)",
                    97_702L, MY_ACCIDENT, "reserved.jpg");

            assertError(() -> requestService.request(ME, MY_ACCIDENT), ErrorCode.INVALID_REQUEST);
        }

        @Test
        @DisplayName("품질 경고(WARN) 사진도 보낸다 — 뺄지는 AI 가 정한다")
        void warnImageIsAccepted() {
            uploadedImage(97_703L, MY_ACCIDENT);
            jdbc.update("update accident_image set quality_status='WARN', quality_reason='흔들림' where image_id=?",
                    97_703L);

            assertThat(requestService.request(ME, MY_ACCIDENT).status()).isEqualTo(AnalysisJobStatus.QUEUED);
        }
    }

    @Nested
    @DisplayName("접수")
    class Accept {

        @Test
        @DisplayName("업로드가 끝난 사진이 있으면 QUEUED 작업을 만든다 — 멱등 키는 아직 없다")
        void createsQueuedJob() {
            uploadedImage(97_704L, MY_ACCIDENT);

            AnalysisProgressResponse response = requestService.request(ME, MY_ACCIDENT);

            assertThat(response.jobId()).isNotNull();
            assertThat(response.status()).isEqualTo(AnalysisJobStatus.QUEUED);
            assertThat(response.totalStages()).isEqualTo(4);
            assertThat(response.stages()).isEmpty();
            assertThat(jdbc.queryForObject("select status from analysis_job where job_id=?",
                    String.class, response.jobId())).isEqualTo("QUEUED");
            // requestId 는 워커가 AI 에 보내기 직전에 심는다. 접수 시점에는 비어 있어야 한다.
            assertThat(jdbc.queryForObject("select request_id from analysis_job where job_id=?",
                    String.class, response.jobId())).isNull();
        }

        @Test
        @DisplayName("같은 사고를 두 번 요청하면 두 번째는 409 다")
        void secondRequestIsConflict() {
            uploadedImage(97_705L, MY_ACCIDENT);
            requestService.request(ME, MY_ACCIDENT);

            assertError(() -> requestService.request(ME, MY_ACCIDENT), ErrorCode.CONFLICT);
            assertThat(jobCount(MY_ACCIDENT)).isEqualTo(1);
        }

        @Test
        @DisplayName("분석이 끝난 사고도 409 다 — 다시 분석하는 것은 재시도(S15P21A307-161)의 몫이다")
        void completedJobIsConflict() {
            uploadedImage(97_706L, MY_ACCIDENT);
            jdbc.update("insert into analysis_job(accident_id,status) values(?,'COMPLETED')", MY_ACCIDENT);

            assertError(() -> requestService.request(ME, MY_ACCIDENT), ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("실패한 사고도 409 다 — 여기서 몰래 다시 큐에 넣지 않는다")
        void failedJobIsConflict() {
            uploadedImage(97_707L, MY_ACCIDENT);
            jdbc.update("insert into analysis_job(accident_id,status,failure_reason) values(?,'FAILED','MODEL_ERROR')",
                    MY_ACCIDENT);

            assertError(() -> requestService.request(ME, MY_ACCIDENT), ErrorCode.CONFLICT);
        }
    }

    /**
     * 재시도 (S15P21A307-161).
     *
     * <p><b>실패한 작업을 되살리지 않고 같은 사고에 작업을 새로 만든다.</b> "사진 전부 제외" 로 끝난
     * 작업은 사진별 결과·산정 불가 견적을 이미 저장했다 — 같은 작업을 다시 돌리면 그 행과 부딪히고,
     * 실패 이력도 덮인다. 화면은 이미 "사고의 최신 작업" 을 읽으므로 새 작업이 그대로 보인다.
     */
    @Nested
    @DisplayName("재시도 (S15P21A307-161)")
    class Retry {

        @Test
        @DisplayName("실패한 작업이 있으면 QUEUED 작업을 새로 만들고 횟수를 하나 올린다 — 실패 작업은 이력으로 남는다")
        void failedJobIsRetriedAsNewJob() {
            uploadedImage(97_801L, MY_ACCIDENT);
            long failedJobId = insertJob(MY_ACCIDENT, "FAILED", 0, "MODEL_ERROR", "2026-09-17T01:00:00Z");

            AnalysisProgressResponse response = requestService.retry(ME, MY_ACCIDENT);

            assertThat(response.jobId()).isNotNull().isNotEqualTo(failedJobId);
            assertThat(response.status()).isEqualTo(AnalysisJobStatus.QUEUED);
            assertThat(response.retryCount()).isEqualTo(1);
            assertThat(response.failureReason()).isNull();
            assertThat(jobCount(MY_ACCIDENT)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select retry_count from analysis_job where job_id=?",
                    Integer.class, response.jobId())).isEqualTo(1);
            // 실패 작업은 손대지 않는다 — 사유가 이력으로 남아야 162(실패 사유 적재)가 쓸 수 있다
            assertThat(jdbc.queryForMap("select status, failure_reason from analysis_job where job_id=?",
                    failedJobId)).containsEntry("STATUS", "FAILED").containsEntry("FAILURE_REASON", "MODEL_ERROR");
            // 멱등 키는 워커가 AI 에 보내기 직전에 새로 심는다
            assertThat(jdbc.queryForObject("select request_id from analysis_job where job_id=?",
                    String.class, response.jobId())).isNull();
        }

        @Test
        @DisplayName("횟수는 직전 작업에서 이어받는다")
        void retryCountCarriesOver() {
            uploadedImage(97_802L, MY_ACCIDENT);
            insertJob(MY_ACCIDENT, "FAILED", 2, "AI_UNREACHABLE", "2026-09-17T01:00:00Z");

            assertThat(requestService.retry(ME, MY_ACCIDENT).retryCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("이미 3회 재시도했으면 409 이고 작업을 만들지 않는다")
        void retryLimitIsConflict() {
            uploadedImage(97_803L, MY_ACCIDENT);
            insertJob(MY_ACCIDENT, "FAILED", 3, "MODEL_ERROR", "2026-09-17T01:00:00Z");

            assertError(() -> requestService.retry(ME, MY_ACCIDENT), ErrorCode.CONFLICT);
            assertThat(jobCount(MY_ACCIDENT)).isEqualTo(1);
        }

        @Test
        @DisplayName("사진이 전부 제외돼 실패한 작업도 재시도할 수 있다 — 저장된 사진별 결과와 부딪히지 않는다")
        void allImagesExcludedIsRetriable() {
            uploadedImage(97_804L, MY_ACCIDENT);
            long failedJobId = insertJob(MY_ACCIDENT, "FAILED", 0, "ALL_IMAGES_EXCLUDED", "2026-09-17T01:00:00Z");
            jdbc.update("insert into analysis_image_result(job_id,image_id,is_excluded,exclusion_reason)"
                    + " values(?,?,true,'NOT_VEHICLE')", failedJobId, 97_804L);

            assertThat(requestService.retry(ME, MY_ACCIDENT).status()).isEqualTo(AnalysisJobStatus.QUEUED);
            assertThat(jobCount(MY_ACCIDENT)).isEqualTo(2);
        }

        @Test
        @DisplayName("분석을 요청한 적이 없으면 409 다 — 재시도가 아니라 분석 요청을 불러야 한다")
        void noJobIsConflict() {
            uploadedImage(97_805L, MY_ACCIDENT);

            assertError(() -> requestService.retry(ME, MY_ACCIDENT), ErrorCode.CONFLICT);
            assertThat(jobCount(MY_ACCIDENT)).isZero();
        }

        @Test
        @DisplayName("진행 중인 작업이 있으면 409 다 — 재시도를 두 번 눌러도 작업은 하나다")
        void inFlightIsConflict() {
            uploadedImage(97_806L, MY_ACCIDENT);
            insertJob(MY_ACCIDENT, "FAILED", 0, "MODEL_ERROR", "2026-09-17T01:00:00Z");
            requestService.retry(ME, MY_ACCIDENT);

            assertError(() -> requestService.retry(ME, MY_ACCIDENT), ErrorCode.CONFLICT);
            assertThat(jobCount(MY_ACCIDENT)).isEqualTo(2);
        }

        @Test
        @DisplayName("분석이 끝난 사고는 409 다 — 완료된 분석을 다시 돌리지 않는다(AI 계약: 사용자 재분석 없음)")
        void completedIsConflict() {
            uploadedImage(97_807L, MY_ACCIDENT);
            insertJob(MY_ACCIDENT, "COMPLETED", 0, null, "2026-09-17T01:00:00Z");

            assertError(() -> requestService.retry(ME, MY_ACCIDENT), ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("판정은 가장 최근 작업으로 한다 — 옛 실패가 있어도 최신이 완료면 409 다")
        void latestJobDecides() {
            uploadedImage(97_808L, MY_ACCIDENT);
            insertJob(MY_ACCIDENT, "FAILED", 0, "MODEL_ERROR", "2026-09-17T01:00:00Z");
            insertJob(MY_ACCIDENT, "COMPLETED", 1, null, "2026-09-17T02:00:00Z");

            assertError(() -> requestService.retry(ME, MY_ACCIDENT), ErrorCode.CONFLICT);
        }

        @Test
        @DisplayName("보낼 사진이 없으면 400 이고 작업을 만들지 않는다")
        void noImageIsBadRequest() {
            insertJob(MY_ACCIDENT, "FAILED", 0, "NO_IMAGE", "2026-09-17T01:00:00Z");

            assertError(() -> requestService.retry(ME, MY_ACCIDENT), ErrorCode.INVALID_REQUEST);
            assertThat(jobCount(MY_ACCIDENT)).isEqualTo(1);
        }

        @Test
        @DisplayName("없는 사고·남의 사고는 404 다")
        void notOwnedIsNotFound() {
            uploadedImage(97_809L, OTHER_ACCIDENT);
            insertJob(OTHER_ACCIDENT, "FAILED", 0, "MODEL_ERROR", "2026-09-17T01:00:00Z");

            assertError(() -> requestService.retry(ME, OTHER_ACCIDENT), ErrorCode.NOT_FOUND);
            assertError(() -> requestService.retry(ME, MISSING_ACCIDENT), ErrorCode.NOT_FOUND);
            assertThat(jobCount(OTHER_ACCIDENT)).isEqualTo(1);
        }

        /**
         * 부위를 골라 다시 분석한 작업이 AI 시간 초과 같은 일로 실패했다면, 다시 시도는 같은 요청을 한 번
         * 더 하는 것이다. 부위를 떨어뜨리면 부품을 못 찾은 처음 결과가 그대로 다시 나온다.
         */
        @Test
        @DisplayName("고른 부위로 다시 분석한 작업을 재시도하면 같은 부위로 보낸다 (S15P21A307-570)")
        void retryKeepsSelectedPart() {
            uploadedImage(97_810L, MY_ACCIDENT);
            insertPartCode(FENDER, true, "AI_LABEL");
            long failedJobId = insertJob(MY_ACCIDENT, "FAILED", 1, "AI_UNREACHABLE", "2026-09-17T01:00:00Z");
            jdbc.update("update analysis_job set selected_part_code=? where job_id=?", FENDER, failedJobId);

            assertThat(selectedPartCode(requestService.retry(ME, MY_ACCIDENT).jobId())).isEqualTo(FENDER);
        }

        @Test
        @DisplayName("부위를 고르지 않은 분석의 재시도는 부위가 비어 있다")
        void plainRetryHasNoSelectedPart() {
            uploadedImage(97_811L, MY_ACCIDENT);
            insertJob(MY_ACCIDENT, "FAILED", 0, "MODEL_ERROR", "2026-09-17T01:00:00Z");

            assertThat(selectedPartCode(requestService.retry(ME, MY_ACCIDENT).jobId())).isNull();
        }
    }

    /**
     * 부위 확정 재분석 (S15P21A307-570).
     *
     * <p>부품을 찾지 못해 산정하지 못한 견적에 사용자가 부위를 골라 준다. 대상은 두 경우다 — 산정 불가
     * {@code PART_NOT_RESOLVED}(작업 완료)와 사진 전부 제외(작업 실패). 둘 다 AI 가
     * {@code PART_NOT_RESOLVED} 견적을 보내고 부품이 빈 손상 검출을 남긴다.
     *
     * <p>409 는 문구까지 본다. 화면은 409 를 모두 "진행 조회로 이어 가기" 로 처리하지만, 서버 로그와
     * 응답 문구로 어느 조건에 걸렸는지는 구분돼야 한다.
     */
    @Nested
    @DisplayName("부위 확정 재분석 (S15P21A307-570)")
    class ResolvePart {

        @BeforeEach
        void partCodes() {
            insertPartCode(FENDER, true, "AI_LABEL");
            insertPartCode(EXTENDED, true, "EXTENDED");
            insertPartCode(INACTIVE, false, "AI_LABEL");
        }

        @Test
        @DisplayName("산정 불가 PART_NOT_RESOLVED 견적에 부위를 고르면 그 부위를 적은 작업을 새로 만들고 횟수를 하나 올린다")
        void partNotResolvedIsAccepted() {
            long estimateId = partNotResolved(97_901L, "COMPLETED", null, 1);

            AnalysisProgressResponse response = requestService.resolvePart(ME, estimateId, FENDER);

            assertThat(response.status()).isEqualTo(AnalysisJobStatus.QUEUED);
            assertThat(response.retryCount()).isEqualTo(2);
            assertThat(jobCount(MY_ACCIDENT)).isEqualTo(2);
            assertThat(selectedPartCode(response.jobId())).isEqualTo(FENDER);
            // 멱등 키는 워커가 AI 에 보내기 직전에 심는다
            assertThat(jdbc.queryForObject("select request_id from analysis_job where job_id=?",
                    String.class, response.jobId())).isNull();
        }

        @Test
        @DisplayName("사진이 전부 제외돼 실패한 분석도 받는다 — 그 견적도 PART_NOT_RESOLVED 다")
        void allImagesExcludedIsAccepted() {
            long estimateId = partNotResolved(97_902L, "FAILED", "ALL_IMAGES_EXCLUDED", 0);

            AnalysisProgressResponse response = requestService.resolvePart(ME, estimateId, FENDER);

            assertThat(response.status()).isEqualTo(AnalysisJobStatus.QUEUED);
            assertThat(selectedPartCode(response.jobId())).isEqualTo(FENDER);
        }

        @Test
        @DisplayName("없는 견적·남의 견적은 404 다")
        void notOwnedIsNotFound() {
            long otherJobId = insertJob(OTHER_ACCIDENT, "COMPLETED", 0, null, "2026-09-17T01:00:00Z");
            long otherEstimateId = insertEstimate(otherJobId, false, "PART_NOT_RESOLVED");

            assertError(() -> requestService.resolvePart(ME, otherEstimateId, FENDER), ErrorCode.NOT_FOUND);
            assertError(() -> requestService.resolvePart(ME, 97_998L, FENDER), ErrorCode.NOT_FOUND);
            assertThat(jobCount(OTHER_ACCIDENT)).isEqualTo(1);
        }

        @Test
        @DisplayName("고를 수 없는 부위는 400 이고 작업을 만들지 않는다 — 모르는 코드·확장 코드·비활성·빈 값")
        void unselectablePartIsBadRequest() {
            long estimateId = partNotResolved(97_903L, "COMPLETED", null, 0);

            for (String partCode : new String[]{"FOO", EXTENDED, INACTIVE, " "}) {
                assertError(() -> requestService.resolvePart(ME, estimateId, partCode), ErrorCode.INVALID_REQUEST);
            }
            assertThat(jobCount(MY_ACCIDENT)).isEqualTo(1);
        }

        @Test
        @DisplayName("진행 중인 작업이 있으면 409 다 — 두 번 눌러도 작업은 하나만 더 생긴다")
        void inFlightIsConflict() {
            long estimateId = partNotResolved(97_904L, "COMPLETED", null, 0);
            requestService.resolvePart(ME, estimateId, FENDER);

            assertConflict(() -> requestService.resolvePart(ME, estimateId, FENDER), "진행 중");
            assertThat(jobCount(MY_ACCIDENT)).isEqualTo(2);
        }

        @Test
        @DisplayName("사고의 최신 작업의 견적이 아니면 409 다 — 옛 견적 id 로 새 결과를 덮지 않는다")
        void olderEstimateIsConflict() {
            long estimateId = partNotResolved(97_905L, "COMPLETED", null, 0);
            insertJob(MY_ACCIDENT, "COMPLETED", 1, null, "2026-09-17T02:00:00Z");

            assertConflict(() -> requestService.resolvePart(ME, estimateId, FENDER), "이미 다시 분석한");
            assertThat(jobCount(MY_ACCIDENT)).isEqualTo(2);
        }

        @Test
        @DisplayName("산정된 견적은 409 다")
        void estimableIsConflict() {
            uploadedImage(97_906L, MY_ACCIDENT);
            long jobId = insertJob(MY_ACCIDENT, "COMPLETED", 0, null, "2026-09-17T01:00:00Z");
            insertImageResult(jobId, 97_906L, false, UNPAIRED);
            long estimateId = insertEstimate(jobId, true, null);

            assertConflict(() -> requestService.resolvePart(ME, estimateId, FENDER), "고를 수 있는 견적이 아닙니다");
        }

        /** 사례가 모자란 건 부위를 골라도 해결되지 않는다. */
        @Test
        @DisplayName("사례 부족으로 산정하지 못한 견적은 409 다")
        void insufficientCasesIsConflict() {
            uploadedImage(97_907L, MY_ACCIDENT);
            long jobId = insertJob(MY_ACCIDENT, "COMPLETED", 0, null, "2026-09-17T01:00:00Z");
            insertImageResult(jobId, 97_907L, false, UNPAIRED);
            long estimateId = insertEstimate(jobId, false, "INSUFFICIENT_CASES");

            assertConflict(() -> requestService.resolvePart(ME, estimateId, FENDER), "고를 수 있는 견적이 아닙니다");
        }

        /** 손상조차 못 찾은 사진은 차가 아니거나 손상이 안 보이는 것이다. 지금처럼 다시 찍게 둔다. */
        @Test
        @DisplayName("부품이 빈 손상 검출이 없으면 409 다")
        void noDamageWithoutPartIsConflict() {
            uploadedImage(97_908L, MY_ACCIDENT);
            long jobId = insertJob(MY_ACCIDENT, "FAILED", 0, "ALL_IMAGES_EXCLUDED", "2026-09-17T01:00:00Z");
            insertImageResult(jobId, 97_908L, true, "[]");
            long estimateId = insertEstimate(jobId, false, "PART_NOT_RESOLVED");

            assertConflict(() -> requestService.resolvePart(ME, estimateId, FENDER), "고를 수 있는 견적이 아닙니다");
        }

        @Test
        @DisplayName("재시도와 합친 횟수를 다 썼으면 409 이고 작업을 만들지 않는다")
        void exhaustedIsConflict() {
            long estimateId = partNotResolved(97_909L, "COMPLETED", null, AnalysisJob.MAX_RETRY_COUNT);

            assertConflict(() -> requestService.resolvePart(ME, estimateId, FENDER), "재시도 횟수");
            assertThat(jobCount(MY_ACCIDENT)).isEqualTo(1);
        }

        @Test
        @DisplayName("보낼 사진이 없으면 400 이고 작업을 만들지 않는다")
        void noImageIsBadRequest() {
            // 업로드 URL 만 받고 완료 통보가 없는 사진 — 축소본이 없어 AI 에 보낼 수 없다
            jdbc.update("insert into accident_image(image_id,accident_id,original_filename) values(?,?,?)",
                    97_910L, MY_ACCIDENT, "reserved.jpg");
            long jobId = insertJob(MY_ACCIDENT, "COMPLETED", 0, null, "2026-09-17T01:00:00Z");
            insertImageResult(jobId, 97_910L, false, UNPAIRED);
            long estimateId = insertEstimate(jobId, false, "PART_NOT_RESOLVED");

            assertError(() -> requestService.resolvePart(ME, estimateId, FENDER), ErrorCode.INVALID_REQUEST);
            assertThat(jobCount(MY_ACCIDENT)).isEqualTo(1);
        }

        /** 부품을 찾지 못해 산정하지 못한 분석 — 업로드한 사진, 부품이 빈 손상, PART_NOT_RESOLVED 견적. */
        private long partNotResolved(long imageId, String status, String failureReason, int retryCount) {
            uploadedImage(imageId, MY_ACCIDENT);
            long jobId = insertJob(MY_ACCIDENT, status, retryCount, failureReason, "2026-09-17T01:00:00Z");
            insertImageResult(jobId, imageId, "FAILED".equals(status), UNPAIRED);
            return insertEstimate(jobId, false, "PART_NOT_RESOLVED");
        }
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private long insertJob(long accidentId, String status, int retryCount, String failureReason,
                           String createdAt) {
        jdbc.update("insert into analysis_job(accident_id,status,retry_count,failure_reason,created_at)"
                        + " values(?,?,?,?,?)",
                accidentId, status, retryCount, failureReason,
                java.sql.Timestamp.from(java.time.Instant.parse(createdAt)));
        Long id = jdbc.queryForObject("select max(job_id) from analysis_job where accident_id=?",
                Long.class, accidentId);
        return id == null ? 0 : id;
    }

    private void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(code);
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String messagePart) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(messagePart)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.CONFLICT);
    }

    private void insertPartCode(String partCode, boolean active, String scope) {
        jdbc.update("""
                insert into part_code (part_code, name_ko, layout_zone, display_order, is_active, code_scope)
                values (?, '시험 부위', 'SIDE_L', 99, ?, ?)
                """, partCode, active, scope);
    }

    private long insertEstimate(long jobId, boolean estimable, String nonEstimableReason) {
        jdbc.update("insert into estimate(job_id,version,is_estimable,non_estimable_reason) values(?,1,?,?)",
                jobId, estimable, nonEstimableReason);
        Long id = jdbc.queryForObject("select estimate_id from estimate where job_id=? and version=1",
                Long.class, jobId);
        return id == null ? 0 : id;
    }

    /** H2 는 JSON 컬럼에 문자열을 넣을 때 {@code FORMAT JSON} 이 필요하다({@code AnalysisResultApiTest} 와 같다). */
    private void insertImageResult(long jobId, long imageId, boolean excluded, String detections) {
        jdbc.update("insert into analysis_image_result(job_id,image_id,detections,is_excluded,exclusion_reason)"
                        + " values(?,?,? FORMAT JSON,?,?)",
                jobId, imageId, detections, excluded, excluded ? "NOT_VEHICLE" : null);
    }

    private String selectedPartCode(long jobId) {
        return jdbc.queryForObject("select selected_part_code from analysis_job where job_id=?",
                String.class, jobId);
    }

    private int jobCount(long accidentId) {
        Integer count = jdbc.queryForObject("select count(*) from analysis_job where accident_id=?",
                Integer.class, accidentId);
        return count == null ? 0 : count;
    }

    /** 완료 통보까지 끝난 사진 — 원본과 축소본 asset 이 있다. */
    private void uploadedImage(long imageId, long accidentId) {
        jdbc.update("insert into accident_image(image_id,accident_id,original_filename,angle_code) values(?,?,?,?)",
                imageId, accidentId, "front.jpg", "FRONT");
        jdbc.update("insert into accident_image_asset(image_id,variant,s3_key) values(?,'ORIGINAL',?)",
                imageId, "accidents/" + accidentId + "/images/" + imageId + "/original.jpg");
        jdbc.update("insert into accident_image_asset(image_id,variant,s3_key) values(?,'RESIZED',?)",
                imageId, "accidents/" + accidentId + "/images/" + imageId + "/resized.jpg");
    }

    private void insertMember(long memberId, String providerUserId) {
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname) values(?,'KAKAO',?,?)",
                memberId, providerUserId, "분석요청");
    }

    private void insertVehicle(long vehicleId, long memberId) {
        jdbc.update("insert into vehicle(vehicle_id,member_id,model_id,model_year) values(?,?,?,2021)",
                vehicleId, memberId, MODEL_ID);
    }

    private void insertAccident(long accidentId, long vehicleId) {
        jdbc.update("""
                insert into accident(accident_id,vehicle_id,vehicle_input_type,snapshot_model_id,
                                     snapshot_manufacturer,snapshot_model_name,snapshot_vehicle_type,
                                     snapshot_car_class,snapshot_model_year)
                values(?,?,'REGISTERED',?,'현대','아반떼','SEDAN','Compact',2021)
                """, accidentId, vehicleId, MODEL_ID);
    }
}
