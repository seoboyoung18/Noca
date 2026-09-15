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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

    @Test
    @DisplayName("PDF 보관소가 없으면 보관소 미준비로 실패한다")
    void missingPdfStorage() {
        processor = new EstimatePdfProcessor(pdfRepository, reportRepository, reportService, generator,
                Optional.empty(), Optional.of(imageStorage));
        given(imageStorage.read("k1")).willReturn(JPEG);
        given(generator.generate(any())).willReturn(new byte[]{'%', 'P', 'D', 'F'});

        assertThatThrownBy(() -> processor.process(REPORT_ID))
                .isInstanceOf(EstimatePdfProcessor.MissingStorageException.class);
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
