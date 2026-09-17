package com.ssafy.a307.estimatevalidation.service;

import com.ssafy.a307.common.llm.LlmChatPort;
import com.ssafy.a307.estimatevalidation.config.ValidationPdfProperties;
import com.ssafy.a307.estimatevalidation.domain.ValidationGrade;
import com.ssafy.a307.estimatevalidation.domain.ValidationStatus;
import com.ssafy.a307.estimatevalidation.dto.ValidationResultResponse;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidation;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidationReport;
import com.ssafy.a307.estimatevalidation.file.DocumentStoragePort;
import com.ssafy.a307.estimatevalidation.file.PdfGenerationPort;
import com.ssafy.a307.estimatevalidation.file.pdf.ReportNarrativeGenerator;
import com.ssafy.a307.estimatevalidation.file.pdf.ValidationPdfAssembler;
import com.ssafy.a307.estimatevalidation.repository.EstimateValidationReportRepository;
import com.ssafy.a307.estimatevalidation.repository.EstimateValidationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 검증 PDF 한 건에서 <b>저장소를 LLM·렌더링보다 먼저 보는지</b> (S15P21A307-526).
 *
 * <p>호출 횟수를 로그가 아니라 단언으로 고정한다 — 순서를 되돌리면 이 테스트가 깨진다.
 * {@link ReportNarrativeGenerator} 와 {@link ValidationPdfAssembler} 는 <b>진짜</b>를 쓰고
 * 그 아래의 {@link LlmChatPort} 만 목으로 둔다. 문장 생성기를 통째로 목으로 바꾸면
 * "LLM 을 불렀는가" 가 아니라 "생성기를 불렀는가" 를 세게 된다.
 *
 * <p>워커는 {@link ValidationPdfWorker#pollOnce()} 한 주기를 그대로 돌린다. 스프링을 띄우지
 * 않으므로 트랜잭션은 없고, 리포트 행은 실제 엔티티라 상태 전이 규칙이 그대로 적용된다.
 */
@DisplayName("검증 PDF — 저장소 확인 순서와 재시도")
class ValidationPdfStorageOrderTest {

    private static final long VALIDATION_ID = 31L;
    private static final long MEMBER_ID = 7L;
    private static final byte[] PDF_BYTES = "%PDF-1.7 test".getBytes();

    private final EstimateValidationReportRepository reportRepository = mock(EstimateValidationReportRepository.class);
    private final EstimateValidationRepository validationRepository = mock(EstimateValidationRepository.class);
    private final EstimateValidationService validationService = mock(EstimateValidationService.class);
    private final LlmChatPort llm = mock(LlmChatPort.class);
    private final PdfGenerationPort pdfGenerationPort = mock(PdfGenerationPort.class);
    private final DocumentStoragePort storage = mock(DocumentStoragePort.class);

    private final ReportNarrativeGenerator narrativeGenerator =
            new ReportNarrativeGenerator(Optional.of(llm), new ObjectMapper());

    /** 선점된 상태(시도 1회째)의 실제 리포트 행. */
    private EstimateValidationReport report;

    @BeforeEach
    void setUp() {
        EstimateValidation validation = mock(EstimateValidation.class);
        given(validation.getStatus()).willReturn(ValidationStatus.COMPLETED);
        given(validation.getMemberId()).willReturn(MEMBER_ID);
        given(validationRepository.findById(VALIDATION_ID)).willReturn(Optional.of(validation));
        given(validationService.result(MEMBER_ID, VALIDATION_ID)).willReturn(result());

        given(llm.complete(any())).willReturn(new LlmChatPort.ChatResult(
                "{\"gradeExplanation\":\"확인을 권장하는 항목이 하나 있습니다.\",\"itemNotes\":[]}",
                "test-model", LlmChatPort.Usage.unknown()));
        given(pdfGenerationPort.generate(any())).willReturn(new PdfGenerationPort.GeneratedPdf(PDF_BYTES));
        given(storage.storeReport(eq(VALIDATION_ID), any())).willReturn(new DocumentStoragePort.StoredDocument(
                "validation-reports/31.pdf", PDF_BYTES.length, "application/pdf"));

        report = EstimateValidationReport.queued(validation);
        report.markProcessing();
        given(reportRepository.findById(VALIDATION_ID)).willReturn(Optional.of(report));
    }

    // ------------------------------------------------------------------ 저장소가 없을 때

    @Nested
    @DisplayName("저장소가 없으면")
    class WithoutStorage {

        private final ValidationPdfProcessor processor = processor(Optional.empty());

        @Test
        @DisplayName("LLM 도 PDF 렌더링도 부르지 않고 저장소 미구성으로 실패한다")
        void failsBeforeCallingLlmOrRenderer() {
            assertThatThrownBy(() -> processor.process(VALIDATION_ID))
                    .isInstanceOf(ValidationPdfProcessor.MissingDocumentStorageException.class);

            verify(llm, never()).complete(any());
            verify(pdfGenerationPort, never()).generate(any());
        }

        @Test
        @DisplayName("워커 한 주기에 큐로 되돌리지 않고 바로 실패로 끝난다 — 다시 해도 같기 때문이다")
        void workerFailsImmediatelyWithoutRetry() {
            runOneCycle(processor);

            assertThat(report.getStatus()).isEqualTo(ValidationStatus.FAILED);
            assertThat(report.getFailureReason()).isEqualTo(ValidationPdfWorker.FAILURE_NO_STORAGE);
            assertThat(report.getRetryCount()).isEqualTo((short) 1);
            assertThat(report.getCompletedAt()).isNotNull();
            verify(llm, never()).complete(any());
            verify(pdfGenerationPort, never()).generate(any());
        }
    }

    // ------------------------------------------------------------------ 저장소가 있을 때 (회귀)

    @Nested
    @DisplayName("저장소가 있으면 지금까지와 같다")
    class WithStorage {

        private final ValidationPdfProcessor processor = processor(Optional.of(storage));

        @Test
        @DisplayName("LLM 1회 · 렌더링 1회 · 저장 1회 후 완료로 옮긴다")
        void generatesStoresAndCompletes() {
            processor.process(VALIDATION_ID);

            verify(llm, times(1)).complete(any());
            verify(pdfGenerationPort, times(1)).generate(any());
            verify(storage, times(1)).storeReport(eq(VALIDATION_ID), any());
            assertThat(report.getStatus()).isEqualTo(ValidationStatus.COMPLETED);
            assertThat(report.getS3KeyPdf()).isEqualTo("validation-reports/31.pdf");
        }

        @Test
        @DisplayName("렌더링이 실패하면 지금처럼 큐로 되돌린다 — 일시적일 수 있는 실패의 재시도는 그대로다")
        void renderingFailureIsStillRetried() {
            given(pdfGenerationPort.generate(any())).willThrow(new IllegalStateException("렌더링 실패"));

            runOneCycle(processor);

            assertThat(report.getStatus()).isEqualTo(ValidationStatus.QUEUED);
            assertThat(report.getRetryCount()).isEqualTo((short) 1);
            verify(storage, never()).storeReport(anyLong(), any());
        }

        @Test
        @DisplayName("렌더링 실패가 재시도 상한에 닿으면 생성 실패 사유로 끝난다")
        void renderingFailureAtLimitFailsWithGenerationReason() {
            given(pdfGenerationPort.generate(any())).willThrow(new IllegalStateException("렌더링 실패"));
            // 세 번째 시도: 선점 → 되돌림을 두 번 거친 뒤 다시 선점한 상태
            report.returnToQueue();
            report.markProcessing();
            report.returnToQueue();
            report.markProcessing();

            runOneCycle(processor);

            assertThat(report.getStatus()).isEqualTo(ValidationStatus.FAILED);
            assertThat(report.getFailureReason()).isEqualTo(ValidationPdfWorker.FAILURE_GENERATION);
            assertThat(report.getRetryCount()).isEqualTo(EstimateValidationReport.MAX_RETRY_COUNT);
        }

        @Test
        @DisplayName("고아 PROCESSING 회수는 지금처럼 큐로 되돌린다")
        void staleProcessingIsStillReturnedToQueue() {
            given(reportRepository.findStaleProcessingIds(any(), any())).willReturn(List.of(VALIDATION_ID));

            worker(processor).pollOnce();

            assertThat(report.getStatus()).isEqualTo(ValidationStatus.QUEUED);
            verify(llm, never()).complete(any());
        }
    }

    // ------------------------------------------------------------------ helpers

    private ValidationPdfProcessor processor(Optional<DocumentStoragePort> storagePort) {
        return new ValidationPdfProcessor(reportRepository, validationRepository, validationService,
                narrativeGenerator, new ValidationPdfAssembler(), pdfGenerationPort, storagePort);
    }

    private ValidationPdfWorker worker(ValidationPdfProcessor processor) {
        return new ValidationPdfWorker(reportRepository, processor,
                new ValidationPdfProperties(true, 3, Duration.ofMinutes(10)));
    }

    /** 큐에 이 건 하나만 있고 선점에 성공하는 주기. 리포트 행은 이미 선점된 상태로 준비돼 있다. */
    private void runOneCycle(ValidationPdfProcessor processor) {
        given(reportRepository.findQueuedIds(any())).willReturn(List.of(VALIDATION_ID));
        given(reportRepository.claimQueued(VALIDATION_ID, EstimateValidationReport.MAX_RETRY_COUNT)).willReturn(1);
        worker(processor).pollOnce();
    }

    private static ValidationResultResponse result() {
        ValidationResultResponse.Item item = new ValidationResultResponse.Item(
                (short) 1, "프론트 펜더", "프론트 펜더", "FRONT_FENDER", "판금", (short) 1,
                null, 450_000, 450_000,
                null, null, 300_000, null, 20,
                null, "소계가 유사 사례 75백분위를 초과합니다.", "확인 권장");
        return new ValidationResultResponse(
                VALIDATION_ID, 11L, null, ValidationStatus.COMPLETED,
                ValidationGrade.CAUTION, ValidationGrade.CAUTION.displayName(),
                "주의: 총 1개 항목 중 1개 항목의 확인을 권장합니다.",
                450_000, null, null, null, null, null,
                1, 1, List.of(item), List.of(),
                null, null, null, null, null, null,
                EstimateValidationService.LEGAL_NOTICE, Instant.now(), Instant.now());
    }
}
