package com.ssafy.a307.estimatevalidation.service;

import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import com.ssafy.a307.estimatevalidation.dto.FileValidationMetadata;
import com.ssafy.a307.estimatevalidation.dto.ManualValidationItemRequest;
import com.ssafy.a307.estimatevalidation.dto.ManualValidationRequest;
import com.ssafy.a307.estimatevalidation.dto.ValidationAcceptedResponse;
import com.ssafy.a307.estimatevalidation.file.DocumentStoragePort;
import com.ssafy.a307.estimatevalidation.file.EstimateOcrPort;
import com.ssafy.a307.estimatevalidation.file.UnsupportedDocumentFormatException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 흐름 D — 파일 접수({@code QUEUED}) → 워커 선점({@code PROCESSING}) → 완료/실패 를 <b>실제 H2</b>로
 * 검증한다. 상태 전이가 {@code ck_ev_done} 을 위반하지 않는지까지 본다.
 *
 * <p><b>LLM 을 실제로 호출하지 않는다.</b> {@code EstimateOcrPort} 를 테스트 대역으로 주입한다 —
 * {@code AccidentImageServiceTest} 의 {@code InMemoryImageStorage} 와 같은 방식이다.
 * 네트워크·비용·비결정성이 붙으면 CI 가 흔들린다.
 *
 * <p>클래스에 {@code @Transactional} 을 붙이지 않는다. 워커가 {@code REQUIRES_NEW} 로 도는데
 * 테스트가 트랜잭션을 쥐고 있으면 그 안쪽 트랜잭션이 시드 데이터를 보지 못한다. 대신 높은 ID
 * 대역을 쓰고 {@code @AfterEach} 에서 직접 지운다.
 *
 * <p>{@code initial-delay} 를 1시간으로 밀어 두어 <b>스케줄러가 스스로 돌지 않게</b> 한다.
 * 주기를 테스트가 직접 부르지 않으면 어느 시점에 무엇이 처리됐는지 단언할 수 없다.
 */
@SpringBootTest(properties = {
        "app.estimate-worker.enabled=true",
        "app.estimate-worker.poll-interval=PT1H",
        "app.estimate-worker.initial-delay=PT1H",
        "app.estimate-worker.batch-size=5",
        "app.estimate-worker.processing-timeout=PT10M"
})
@Import(EstimateValidationWorkerTest.FakePortsConfig.class)
@DisplayName("견적서 파일 검증 워커")
class EstimateValidationWorkerTest {

    private static final long MEMBER_ID = 95_001L;
    private static final long MODEL_ID = 95_002L;
    private static final long VEHICLE_ID = 95_003L;
    private static final long ACCIDENT_ID = 95_004L;

    @TestConfiguration
    static class FakePortsConfig {

        @Bean
        InMemoryDocumentStorage inMemoryDocumentStorage() {
            return new InMemoryDocumentStorage();
        }

        @Bean
        ScriptedOcrPort scriptedOcrPort() {
            return new ScriptedOcrPort();
        }
    }

    /** 저장·읽기·삭제를 메모리에서 흉내내는 문서 저장소 더블. */
    static class InMemoryDocumentStorage implements DocumentStoragePort {

        final Map<String, byte[]> objects = new LinkedHashMap<>();
        final List<String> deleted = new ArrayList<>();
        int sequence;

        void reset() {
            objects.clear();
            deleted.clear();
            sequence = 0;
        }

        @Override
        public StoredDocument store(StoreDocument request) {
            byte[] content = request.content();
            String key = "estimates/2026/09/test-" + (++sequence) + "." + request.extension();
            objects.put(key, content);
            return new StoredDocument(key, content.length, request.contentType());
        }

        @Override
        public byte[] read(String storageKey) {
            byte[] content = objects.get(storageKey);
            if (content == null) {
                throw new com.ssafy.a307.common.exception.BusinessException(
                        com.ssafy.a307.common.exception.ErrorCode.NOT_FOUND, "견적서 파일을 찾을 수 없습니다.");
            }
            return content.clone();
        }

        @Override
        public void delete(String storageKey) {
            deleted.add(storageKey);
            objects.remove(storageKey);
        }

        @Override
        public StoredDocument storeReport(long validationId, byte[] pdfContent) {
            String key = "estimates/reports/2026/09/" + validationId + "-test.pdf";
            objects.put(key, pdfContent.clone());
            return new StoredDocument(key, pdfContent.length, "application/pdf");
        }

        @Override
        public URI createPresignedDownloadUrl(String storageKey, Duration validity, String downloadFilename) {
            throw new UnsupportedOperationException("테스트에서 쓰지 않는다");
        }
    }

    /** 다음 호출이 무엇을 낼지 테스트가 정해 주는 판독 대역. */
    static class ScriptedOcrPort implements EstimateOcrPort {

        EstimateOcrPort.OcrExtraction next;
        RuntimeException failure;
        int calls;

        void reset() {
            next = null;
            failure = null;
            calls = 0;
        }

        @Override
        public OcrExtraction extract(OcrDocument document) {
            calls++;
            if (failure != null) throw failure;
            return next;
        }
    }

    @Autowired EstimateFileValidationService fileService;
    @Autowired EstimateValidationService validationService;
    @Autowired EstimateValidationWorker worker;
    @Autowired EstimateFileProcessor processor;
    @Autowired InMemoryDocumentStorage storage;
    @Autowired ScriptedOcrPort ocr;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        cleanUp();
        storage.reset();
        ocr.reset();
        jdbc.update("insert into member(member_id,provider,provider_user_id,nickname,role,status)"
                + " values(?,'KAKAO','worker-member','tester','USER','ACTIVE')", MEMBER_ID);
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
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    private void cleanUp() {
        jdbc.update("delete from estimate_validation_question where validation_id in"
                + " (select validation_id from estimate_validation where member_id = ?)", MEMBER_ID);
        jdbc.update("delete from estimate_validation_item where validation_id in"
                + " (select validation_id from estimate_validation where member_id = ?)", MEMBER_ID);
        jdbc.update("delete from estimate_validation_report where validation_id in"
                + " (select validation_id from estimate_validation where member_id = ?)", MEMBER_ID);
        jdbc.update("delete from estimate_validation where member_id = ?", MEMBER_ID);
        jdbc.update("delete from accident where accident_id = ?", ACCIDENT_ID);
        jdbc.update("delete from vehicle where vehicle_id = ?", VEHICLE_ID);
        jdbc.update("delete from vehicle_model where model_id = ?", MODEL_ID);
        jdbc.update("delete from member where member_id = ?", MEMBER_ID);
    }

    // ------------------------------------------------------------------ 상태 전이

    @Test
    @DisplayName("QUEUED 로 접수된 건이 워커를 지나 COMPLETED 가 된다")
    void queuedFileReachesCompleted() {
        long validationId = registerPdf();
        assertThat(statusOf(validationId)).isEqualTo("QUEUED");
        assertThat(completedAtOf(validationId)).isNull();
        ocr.next = extraction(line(1, "프론트 펜더", "판금", 1, 100_000, 50_000, 0.93));

        worker.pollOnce();

        assertThat(statusOf(validationId)).isEqualTo("COMPLETED");
        assertThat(completedAtOf(validationId)).isNotNull();
        assertThat(ocr.calls).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select claimed_total from estimate_validation where validation_id = ?",
                Integer.class, validationId)).isEqualTo(150_000);
        assertThat(jdbc.queryForObject(
                "select count(*) from estimate_validation_item where validation_id = ?",
                Integer.class, validationId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select failure_reason from estimate_validation where validation_id = ?",
                String.class, validationId)).isNull();
    }

    @Test
    @DisplayName("선점하면 PROCESSING 이고 completed_at 은 비어 있다 — ck_ev_done 을 어기지 않는다")
    void claimingMovesToProcessingWithoutCompletedAt() {
        long validationId = registerPdf();

        assertThat(processor.claim(validationId)).isTrue();

        assertThat(statusOf(validationId)).isEqualTo("PROCESSING");
        assertThat(completedAtOf(validationId)).isNull();
    }

    @Test
    @DisplayName("같은 건을 두 번 선점하면 두 번째는 0행이라 건너뛴다")
    void secondClaimLoses() {
        long validationId = registerPdf();

        assertThat(processor.claim(validationId)).isTrue();
        assertThat(processor.claim(validationId)).isFalse();
    }

    @Test
    @DisplayName("워커는 이미 완료된 건을 다시 집지 않는다")
    void completedRowsAreNotPickedAgain() {
        long validationId = registerPdf();
        ocr.next = extraction(line(1, "프론트 펜더", "판금", 1, 100_000, 50_000, 0.93));
        worker.pollOnce();

        worker.pollOnce();

        assertThat(ocr.calls).isEqualTo(1);
        assertThat(statusOf(validationId)).isEqualTo("COMPLETED");
    }

    // ------------------------------------------------------------------ 실패

    @Nested
    @DisplayName("실패 처리")
    class Failures {

        @Test
        @DisplayName("판독이 실패하면 FAILED 로 끝나고 사용자용 사유가 남는다")
        void extractionFailureIsRecorded() {
            long validationId = registerPdf();
            ocr.failure = new IllegalStateException("모델 응답이 깨졌다 sk-secret-key-should-never-leak");

            worker.pollOnce();

            assertThat(statusOf(validationId)).isEqualTo("FAILED");
            assertThat(completedAtOf(validationId)).isNotNull();
            String reason = failureReasonOf(validationId);
            assertThat(reason).isEqualTo(EstimateValidationFailure.EXTRACTION.userMessage());
            // 내부 예외 메시지·키가 사용자에게 새지 않는다.
            assertThat(reason).doesNotContain("sk-secret").doesNotContain("IllegalStateException");
        }

        @Test
        @DisplayName("쓸 수 있는 항목이 없으면 FAILED 다 — 빈 견적서를 적정으로 판정하지 않는다")
        void emptyExtractionFails() {
            long validationId = registerPdf();
            ocr.next = new EstimateOcrPort.OcrExtraction(List.of(), null, 0.4);

            worker.pollOnce();

            assertThat(statusOf(validationId)).isEqualTo("FAILED");
            assertThat(failureReasonOf(validationId))
                    .isEqualTo(EstimateValidationFailure.NO_ITEMS.userMessage());
            assertThat(jdbc.queryForObject(
                    "select llm_grade from estimate_validation where validation_id = ?",
                    String.class, validationId)).isNull();
        }

        @Test
        @DisplayName("원본이 저장소에 없으면 다시 등록하라고 안내한다")
        void missingOriginalIsStorageFailure() {
            long validationId = registerPdf();
            storage.objects.clear();
            ocr.failure = new com.ssafy.a307.common.exception.BusinessException(
                    com.ssafy.a307.common.exception.ErrorCode.NOT_FOUND, "견적서 파일을 찾을 수 없습니다.");

            worker.pollOnce();

            assertThat(statusOf(validationId)).isEqualTo("FAILED");
            assertThat(failureReasonOf(validationId))
                    .isEqualTo(EstimateValidationFailure.STORAGE_READ.userMessage());
        }

        @Test
        @DisplayName("지원하지 않는 작업유형만 있으면 항목 해석 실패다")
        void unmappableWorkTypeFails() {
            long validationId = registerPdf();
            ocr.next = extraction(line(1, "프론트 펜더", "광택", 1, 100_000, 50_000, 0.9));

            worker.pollOnce();

            assertThat(statusOf(validationId)).isEqualTo("FAILED");
            assertThat(failureReasonOf(validationId))
                    .isEqualTo(EstimateValidationFailure.ITEM_MAPPING.userMessage());
        }

        @Test
        @DisplayName("공급자가 PDF 를 못 받으면 그 사실을 그대로 알린다 — 항목 없음으로 둔갑시키지 않는다")
        void unsupportedFormatIsReportedAsSuch() {
            long validationId = registerPdf();
            ocr.failure = new UnsupportedDocumentFormatException("PDF는 현재 공급자에서 지원되지 않습니다.");

            worker.pollOnce();

            assertThat(statusOf(validationId)).isEqualTo("FAILED");
            assertThat(failureReasonOf(validationId))
                    .isEqualTo(EstimateValidationFailure.UNSUPPORTED_FORMAT.userMessage())
                    .isEqualTo("PDF는 현재 공급자에서 지원되지 않습니다.");
            // "항목을 찾지 못했다" 로 둔갑하면 사용자가 원인을 영영 모른다.
            assertThat(failureReasonOf(validationId))
                    .isNotEqualTo(EstimateValidationFailure.NO_ITEMS.userMessage());
        }

        @Test
        @DisplayName("실패 사유는 VARCHAR(200) 안에 들어간다")
        void failureReasonFitsColumn() {
            for (EstimateValidationFailure failure : EstimateValidationFailure.values()) {
                assertThat(failure.userMessage().length())
                        .isLessThanOrEqualTo(com.ssafy.a307.estimatevalidation.entity.EstimateValidation
                                .MAX_FAILURE_REASON_LENGTH);
            }
        }
    }

    // ------------------------------------------------------------------ 고아 회수

    @Test
    @DisplayName("오래 PROCESSING 인 고아 건은 FAILED 로 종결한다")
    void staleProcessingIsAbandoned() {
        long validationId = registerPdf();
        processor.claim(validationId);
        jdbc.update("update estimate_validation set created_at = ? where validation_id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minus(Duration.ofHours(2))), validationId);

        worker.pollOnce();

        assertThat(statusOf(validationId)).isEqualTo("FAILED");
        assertThat(failureReasonOf(validationId))
                .isEqualTo(EstimateValidationFailure.ABANDONED.userMessage());
        assertThat(ocr.calls).isZero();
    }

    // ------------------------------------------------------------------ 파이프라인 합류

    @Test
    @DisplayName("같은 항목이면 파일 경로와 직접 입력이 같은 등급·같은 플래그를 낸다")
    void fileAndManualPathsAgree() {
        long fileValidationId = registerPdf();
        ocr.next = extraction(
                line(1, "프론트 펜더", "판금", 1, 400_000, 200_000, 0.95),
                line(2, "프론트 펜더", "도장", 1, 50_000, 30_000, 0.9));
        worker.pollOnce();

        ValidationAcceptedResponse manual = validationService.registerManual(MEMBER_ID,
                new ManualValidationRequest(ACCIDENT_ID, null, EstimateFileType.MANUAL, null, List.of(
                        new ManualValidationItemRequest(1, "프론트 펜더", "판금", 1, 400_000, 200_000),
                        new ManualValidationItemRequest(2, "프론트 펜더", "도장", 1, 50_000, 30_000))));

        var fileResult = validationService.result(MEMBER_ID, fileValidationId);
        var manualResult = validationService.result(MEMBER_ID, manual.validationId());

        assertThat(fileResult.grade()).isEqualTo(manualResult.grade());
        assertThat(fileResult.claimedTotal()).isEqualTo(manualResult.claimedTotal());
        assertThat(fileResult.reviewItemCount()).isEqualTo(manualResult.reviewItemCount());
        assertThat(fileResult.totalItemCount()).isEqualTo(manualResult.totalItemCount());
        assertThat(fileResult.items()).extracting("flag")
                .isEqualTo(manualResult.items().stream().map(item -> item.flag()).toList());
    }

    @Test
    @DisplayName("문서상 총액이 어긋나도 항목 소계 합계를 총액으로 쓴다")
    void claimedTotalComesFromLineItems() {
        long validationId = registerPdf();
        // 문서에는 부가세가 붙은 총액이 적혀 있다고 가정한다.
        ocr.next = new EstimateOcrPort.OcrExtraction(
                List.of(line(1, "프론트 펜더", "판금", 1, 100_000, 50_000, 0.9)), 165_000L, 0.9);

        worker.pollOnce();

        assertThat(jdbc.queryForObject(
                "select claimed_total from estimate_validation where validation_id = ?",
                Integer.class, validationId)).isEqualTo(150_000);
    }

    // ------------------------------------------------------------------ helpers

    private long registerPdf() {
        var file = new MockMultipartFile("file", "estimate.pdf", MediaType.APPLICATION_PDF_VALUE,
                new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '7'});
        return fileService.registerFile(MEMBER_ID, new FileValidationMetadata(ACCIDENT_ID, null), file)
                .validationId();
    }

    private static EstimateOcrPort.OcrLineItem line(
            int lineNo, String name, String workType, long quantity, long partCost, long laborCost, double confidence) {
        return new EstimateOcrPort.OcrLineItem(
                lineNo, name, workType, quantity, partCost, laborCost, null, confidence);
    }

    private static EstimateOcrPort.OcrExtraction extraction(EstimateOcrPort.OcrLineItem... items) {
        return new EstimateOcrPort.OcrExtraction(List.of(items), null, 0.9);
    }

    private String statusOf(long validationId) {
        return jdbc.queryForObject(
                "select status from estimate_validation where validation_id = ?", String.class, validationId);
    }

    private String failureReasonOf(long validationId) {
        return jdbc.queryForObject(
                "select failure_reason from estimate_validation where validation_id = ?",
                String.class, validationId);
    }

    private Object completedAtOf(long validationId) {
        return jdbc.queryForObject(
                "select completed_at from estimate_validation where validation_id = ?",
                Object.class, validationId);
    }
}
