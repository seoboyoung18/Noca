package com.ssafy.a307.estimate.pdf;

import com.ssafy.a307.accident.image.AccidentImageStoragePort;
import com.ssafy.a307.estimate.dto.EstimateBasisResponse;
import com.ssafy.a307.estimate.dto.EstimateReportResponse;
import com.ssafy.a307.estimate.dto.EstimateResponse;
import com.ssafy.a307.estimate.pdf.EstimatePdfRepository.JobView;
import com.ssafy.a307.estimate.pdf.EstimatePdfStoragePort.StoredPdf;
import com.ssafy.a307.estimate.repository.EstimateReportRepository;
import com.ssafy.a307.estimate.repository.EstimateReportRepository.ReportContextView;
import com.ssafy.a307.estimate.repository.EstimateReportRepository.ReportImageView;
import com.ssafy.a307.estimate.service.EstimateReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("견적 PDF 한 건 처리")
class EstimatePdfProcessorTest {

    private static final long REPORT_ID = 5L;
    private static final long ESTIMATE_ID = 10L;
    private static final long OWNER_ID = 7L;
    private static final long JOB_ID = 20L;
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2};

    private final EstimatePdfRepository pdfRepository = mock(EstimatePdfRepository.class);
    private final EstimateReportRepository reportRepository = mock(EstimateReportRepository.class);
    private final EstimateReportService reportService = mock(EstimateReportService.class);
    private final EstimatePdfGenerator generator = mock(EstimatePdfGenerator.class);
    private final EstimatePdfStoragePort pdfStorage = mock(EstimatePdfStoragePort.class);
    private final AccidentImageStoragePort imageStorage = mock(AccidentImageStoragePort.class);

    private EstimatePdfProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new EstimatePdfProcessor(pdfRepository, reportRepository, reportService, generator,
                Optional.of(pdfStorage), Optional.of(imageStorage));

        JobView job = mock(JobView.class);
        given(job.getEstimateId()).willReturn(ESTIMATE_ID);
        given(job.getReportNo()).willReturn("R-20260911-0001");
        given(pdfRepository.findJob(REPORT_ID)).willReturn(Optional.of(job));
        given(pdfRepository.findOwnerMemberId(ESTIMATE_ID)).willReturn(Optional.of(OWNER_ID));

        given(reportService.report(ESTIMATE_ID, OWNER_ID)).willReturn(report());

        ReportContextView context = mock(ReportContextView.class);
        given(context.getJobId()).willReturn(JOB_ID);
        given(reportRepository.findContext(ESTIMATE_ID, OWNER_ID)).willReturn(Optional.of(context));
        // 목을 먼저 만든다 — given(...) 안에서 다른 목을 stub 하면 UnfinishedStubbingException 이다
        ReportImageView withOverlay = image(1L, "k1");
        ReportImageView withoutOverlay = image(2L, null);
        given(reportRepository.findAnalyzedImages(JOB_ID)).willReturn(List.of(withOverlay, withoutOverlay));
    }

    @Test
    @DisplayName("오버레이를 data URI 로 박아 PDF 를 만들고 저장한 뒤 완료로 옮긴다")
    void processesAndCompletes() {
        given(imageStorage.read("k1")).willReturn(JPEG);
        given(generator.generate(any())).willReturn(new byte[]{'%', 'P', 'D', 'F'});
        given(pdfStorage.store(eq("R-20260911-0001"), any())).willReturn(new StoredPdf("estimate-reports/a.pdf", 4));
        given(pdfRepository.complete(REPORT_ID, "estimate-reports/a.pdf")).willReturn(1);

        processor.process(REPORT_ID);

        ArgumentCaptor<EstimatePdfDocument> document = ArgumentCaptor.forClass(EstimatePdfDocument.class);
        verify(generator).generate(document.capture());
        assertThat(document.getValue().reportNo()).isEqualTo("R-20260911-0001");
        assertThat(document.getValue().images()).hasSize(2);
        assertThat(document.getValue().images().get(0).dataUri()).startsWith("data:image/jpeg;base64,");
        // 오버레이 키가 없는 사진은 "분석 이미지 없음" 이 그려진다
        assertThat(document.getValue().images().get(1).hasOverlay()).isFalse();
        verify(pdfRepository).complete(REPORT_ID, "estimate-reports/a.pdf");
    }

    /** 일시적 저장소 오류로 사진이 빠진 PDF 가 번호를 달고 굳으면 안 된다. 실패시켜 재시도한다. */
    @Test
    @DisplayName("오버레이를 읽지 못하면 없음으로 넘기지 않고 실패한다")
    void overlayReadFailureFails() {
        given(imageStorage.read("k1")).willThrow(new IllegalStateException("S3 오류"));

        assertThatThrownBy(() -> processor.process(REPORT_ID)).isInstanceOf(IllegalStateException.class);

        verify(generator, never()).generate(any());
        verify(pdfRepository, never()).complete(anyLong(), anyString());
    }

    /**
     * S15P21A307-528. 전에는 오버레이를 읽고 PDF 를 다 만든 뒤에야 보관소가 없음을 알고 버렸다.
     *
     * <p>이 테스트는 예전에 예외 타입만 봤다 — 그래서 순서가 어느 쪽이든 통과해 회귀를 잡지 못했다.
     * 이제 "읽지도 만들지도 않았다" 를 단언한다.
     */
    @Test
    @DisplayName("PDF 보관소가 없으면 오버레이를 읽지도 PDF 를 만들지도 않고 보관소 미준비로 실패한다")
    void missingPdfStorage() {
        processor = new EstimatePdfProcessor(pdfRepository, reportRepository, reportService, generator,
                Optional.empty(), Optional.of(imageStorage));
        // 불렸다면 성공했을 스텁이다. 아래 never() 가 "실패해서 못 불렀다" 가 아니라 "안 불렀다" 임을 보인다.
        given(imageStorage.read("k1")).willReturn(JPEG);
        given(generator.generate(any())).willReturn(new byte[]{'%', 'P', 'D', 'F'});

        assertThatThrownBy(() -> processor.process(REPORT_ID))
                .isInstanceOf(EstimatePdfProcessor.MissingStorageException.class);

        verify(imageStorage, never()).read(anyString());
        verify(generator, never()).generate(any());
        verify(pdfRepository, never()).complete(anyLong(), anyString());
    }

    /**
     * S15P21A307-528 의 함정. 사고 이미지 저장소는 <b>오버레이 키가 있을 때만</b> 필요하다.
     * 오버레이는 2026-09-11 에 폐기돼 키가 늘 NULL 이므로, 보관소 확인과 함께 이 저장소까지 앞으로 당기면
     * staging 버킷이 없는 환경의 견적 PDF 가 전부 실패한다. 그것을 막는 테스트다.
     */
    @Test
    @DisplayName("오버레이 키가 없는 리포트는 사고 이미지 저장소 없이도 만들어진다")
    void overlayLessReportNeedsNoImageStorage() {
        processor = new EstimatePdfProcessor(pdfRepository, reportRepository, reportService, generator,
                Optional.of(pdfStorage), Optional.empty());
        ReportImageView nullKey = image(1L, null);
        ReportImageView blankKey = image(2L, " ");
        given(reportRepository.findAnalyzedImages(JOB_ID)).willReturn(List.of(nullKey, blankKey));
        given(generator.generate(any())).willReturn(new byte[]{'%', 'P', 'D', 'F'});
        given(pdfStorage.store(eq("R-20260911-0001"), any())).willReturn(new StoredPdf("estimate-reports/a.pdf", 4));
        given(pdfRepository.complete(REPORT_ID, "estimate-reports/a.pdf")).willReturn(1);

        processor.process(REPORT_ID);

        ArgumentCaptor<EstimatePdfDocument> document = ArgumentCaptor.forClass(EstimatePdfDocument.class);
        verify(generator).generate(document.capture());
        assertThat(document.getValue().images()).extracting(EstimatePdfDocument.Image::hasOverlay)
                .containsExactly(false, false);
        verify(pdfRepository).complete(REPORT_ID, "estimate-reports/a.pdf");
    }

    @Test
    @DisplayName("재시도 여지가 있으면 큐로 되돌리고 실패로 종결하지 않는다")
    void markFailedReturnsToQueue() {
        given(pdfRepository.returnToQueue(REPORT_ID, EstimatePdfProcessor.MAX_RETRY_COUNT)).willReturn(1);

        processor.markFailed(REPORT_ID, "PDF를 만들지 못했습니다.");

        verify(pdfRepository, never()).fail(anyLong(), anyString());
    }

    @Test
    @DisplayName("재시도 상한이면 실패로 종결한다")
    void markFailedAtLimitFails() {
        given(pdfRepository.returnToQueue(REPORT_ID, EstimatePdfProcessor.MAX_RETRY_COUNT)).willReturn(0);

        processor.markFailed(REPORT_ID, "PDF를 만들지 못했습니다.");

        verify(pdfRepository).fail(REPORT_ID, "PDF를 만들지 못했습니다.");
    }

    @Test
    @DisplayName("JPEG·PNG 만 data URI 로 바꾸고 모르는 형식은 거절한다")
    void dataUriFormats() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A};

        assertThat(EstimatePdfProcessor.toDataUri(JPEG)).startsWith("data:image/jpeg;base64,");
        assertThat(EstimatePdfProcessor.toDataUri(png)).startsWith("data:image/png;base64,");
        assertThatThrownBy(() -> EstimatePdfProcessor.toDataUri(new byte[]{'G', 'I', 'F', '8'}))
                .isInstanceOf(IllegalStateException.class);
    }

    // ---------- 워커 한 주기 — 실패를 어떻게 기록하나 (S15P21A307-528) ----------

    /**
     * 저장소 미구성만 재시도하지 않고, 그 밖의 실패 경로는 지금처럼 재시도하는지.
     *
     * <p>처리기는 <b>진짜</b>를 쓴다 — 워커가 어떤 예외를 받아 어느 기록 메서드로 보내는지, 그 메서드가
     * 어느 UPDATE 를 부르는지까지가 한 동작이다. 리포지토리 목에서 {@code fail} · {@code returnToQueue} 를 센다.
     */
    @Nested
    @DisplayName("워커 한 주기 — 실패 기록과 재시도")
    class WorkerCycle {

        private static final int BATCH_SIZE = 5;
        private static final short MAX = EstimatePdfProcessor.MAX_RETRY_COUNT;

        @BeforeEach
        void queueOne() {
            given(pdfRepository.findQueuedIds(MAX, BATCH_SIZE)).willReturn(List.of(REPORT_ID));
            given(pdfRepository.claim(REPORT_ID, MAX)).willReturn(1);
        }

        @Test
        @DisplayName("PDF 보관소가 없으면 큐로 되돌리지 않고 보관소 미준비로 바로 종결한다")
        void missingPdfStorageFailsWithoutRetry() {
            EstimatePdfProcessor noPdfStorage = new EstimatePdfProcessor(pdfRepository, reportRepository,
                    reportService, generator, Optional.empty(), Optional.of(imageStorage));

            worker(noPdfStorage).pollOnce();

            verify(pdfRepository).fail(REPORT_ID, EstimatePdfWorker.FAILURE_NO_STORAGE);
            verify(pdfRepository, never()).returnToQueue(anyLong(), anyShort());
            verify(imageStorage, never()).read(anyString());
            verify(generator, never()).generate(any());
        }

        /** 두 저장소를 나누지 않은 결정(§4)을 고정한다 — 이미지 저장소도 기동 시 주입이라 다시 해도 같다. */
        @Test
        @DisplayName("오버레이 키가 있는데 사고 이미지 저장소가 없어도 재시도하지 않는다")
        void missingImageStorageFailsWithoutRetry() {
            EstimatePdfProcessor noImageStorage = new EstimatePdfProcessor(pdfRepository, reportRepository,
                    reportService, generator, Optional.of(pdfStorage), Optional.empty());

            worker(noImageStorage).pollOnce();

            verify(pdfRepository).fail(REPORT_ID, EstimatePdfWorker.FAILURE_NO_STORAGE);
            verify(pdfRepository, never()).returnToQueue(anyLong(), anyShort());
            verify(generator, never()).generate(any());
        }

        /** 저장소가 <b>있는데</b> 읽기가 실패한 것은 일시적일 수 있다. "없음" 과 섞이면 안 된다. */
        @Test
        @DisplayName("오버레이 읽기가 실패하면 지금처럼 큐로 되돌린다")
        void overlayReadFailureIsStillRetried() {
            given(imageStorage.read("k1")).willThrow(new IllegalStateException("S3 오류"));
            given(pdfRepository.returnToQueue(REPORT_ID, MAX)).willReturn(1);

            worker(processor).pollOnce();

            verify(pdfRepository).returnToQueue(REPORT_ID, MAX);
            verify(pdfRepository, never()).fail(anyLong(), anyString());
        }

        @Test
        @DisplayName("렌더링이 실패하면 지금처럼 큐로 되돌린다")
        void renderingFailureIsStillRetried() {
            given(imageStorage.read("k1")).willReturn(JPEG);
            given(generator.generate(any())).willThrow(new IllegalStateException("렌더링 실패"));
            given(pdfRepository.returnToQueue(REPORT_ID, MAX)).willReturn(1);

            worker(processor).pollOnce();

            verify(pdfRepository).returnToQueue(REPORT_ID, MAX);
            verify(pdfRepository, never()).fail(anyLong(), anyString());
        }

        @Test
        @DisplayName("렌더링 실패가 재시도 상한이면 생성 실패 사유로 종결한다")
        void renderingFailureAtLimitFailsWithGenerationReason() {
            given(imageStorage.read("k1")).willReturn(JPEG);
            given(generator.generate(any())).willThrow(new IllegalStateException("렌더링 실패"));
            given(pdfRepository.returnToQueue(REPORT_ID, MAX)).willReturn(0);

            worker(processor).pollOnce();

            verify(pdfRepository).fail(REPORT_ID, EstimatePdfWorker.FAILURE_GENERATION);
        }

        @Test
        @DisplayName("고아 PROCESSING 회수는 지금처럼 큐로 되돌린다")
        void staleProcessingIsStillReturnedToQueue() {
            given(pdfRepository.findQueuedIds(MAX, BATCH_SIZE)).willReturn(List.of());
            given(pdfRepository.findStaleProcessingIds(any(), eq(BATCH_SIZE))).willReturn(List.of(REPORT_ID));
            given(pdfRepository.returnToQueue(REPORT_ID, MAX)).willReturn(1);

            worker(processor).pollOnce();

            verify(pdfRepository).returnToQueue(REPORT_ID, MAX);
            verify(pdfRepository, never()).fail(anyLong(), anyString());
        }

        private EstimatePdfWorker worker(EstimatePdfProcessor target) {
            return new EstimatePdfWorker(pdfRepository, target,
                    new EstimatePdfProperties(true, BATCH_SIZE, Duration.ofMinutes(5)));
        }
    }

    // ---------- 데이터 ----------

    private static ReportImageView image(long imageId, String overlayKey) {
        ReportImageView view = mock(ReportImageView.class);
        given(view.getImageId()).willReturn(imageId);
        given(view.getOverlayKey()).willReturn(overlayKey);
        return view;
    }

    private static EstimateReportResponse report() {
        Instant now = Instant.parse("2026-09-11T03:00:00Z");
        EstimateResponse estimate = new EstimateResponse(ESTIMATE_ID, JOB_ID, (short) 1, true, null,
                null, null, 700_000, 800_000, 900_000, 12, "HIGH", List.of(), List.of(), now);
        return new EstimateReportResponse(
                new EstimateReportResponse.Vehicle("현대", "아반떼", "SEDAN", "Compact", (short) 2020),
                new EstimateReportResponse.Accident(3L, now),
                List.of(new EstimateReportResponse.Image(1L, "FRONT", "https://signed/1"),
                        new EstimateReportResponse.Image(2L, "REAR", null)),
                estimate,
                new EstimateBasisResponse(ESTIMATE_ID, (short) 1, List.of()),
                null,
                "고지 문구",
                // 안내 고지(S15P21A307-488)는 아직 PDF 가 그리지 않는다 — 실을 섹션이 없다.
                null,
                now);
    }
}
