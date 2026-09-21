package com.ssafy.a307.estimate.service;

import com.ssafy.a307.accident.image.AccidentImageDownloadUrls;
import com.ssafy.a307.accident.image.AccidentImageStoragePort.PresignedDownload;
import com.ssafy.a307.estimate.dto.EstimateBasisResponse;
import com.ssafy.a307.estimate.dto.EstimateItemResponse;
import com.ssafy.a307.estimate.dto.EstimateReportResponse.Image;
import com.ssafy.a307.estimate.dto.EstimateResponse;
import com.ssafy.a307.estimate.narrative.EstimateNarrativeReader;
import com.ssafy.a307.estimate.narrative.EstimateNarrativeRepository;
import com.ssafy.a307.estimate.repository.EstimateReportRepository;
import com.ssafy.a307.estimate.repository.EstimateReportRepository.ReportContextView;
import com.ssafy.a307.estimate.repository.EstimateReportRepository.ReportImageView;
import com.ssafy.a307.estimatevalidation.service.EstimateValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 리포트 미리보기의 사진 (S15P21A307-560).
 *
 * <p>오버레이는 2026-09-11 에 폐기돼 {@code overlayUrl} 이 늘 비어 있었고, 그것만 보던 미리보기는
 * 사진 칸이 통째로 비어 있었다 — 같은 견적의 PDF 에는 사진이 나오는데. 여기서는 <b>PDF 와 같은
 * 순서로 사진을 고르는지</b>, 박스가 PDF 와 같은 값인지를 본다.
 *
 * <p>{@code EstimateReportApiTest} 는 로컬 PostgreSQL 이 있어야 돌아 평소에는 건너뛴다. 그래서
 * 사진 선택은 목으로 세운 이 테스트가 지킨다.
 */
@DisplayName("리포트 미리보기 사진")
class EstimateReportServiceImageTest {

    private static final long ESTIMATE_ID = 10L;
    private static final long MEMBER_ID = 7L;
    private static final long JOB_ID = 20L;

    /** 1000×500 사진의 (100,50)에서 400×100 → 왼쪽 10%, 위 10%, 폭 40%, 높이 20%. */
    private static final String DETECTIONS = "[{\"partCode\":\"FRONT_BUMPER\",\"geometry\":"
            + "{\"bbox\":{\"x\":100,\"y\":50,\"width\":400,\"height\":100}}}]";

    private final EstimateReportRepository reportRepository = mock(EstimateReportRepository.class);
    private final EstimateQueryService estimateQueryService = mock(EstimateQueryService.class);
    private final AccidentImageDownloadUrls downloadUrls = mock(AccidentImageDownloadUrls.class);
    private final EstimateNoticeProvider noticeProvider = mock(EstimateNoticeProvider.class);

    private final EstimateReportService service = new EstimateReportService(
            reportRepository, estimateQueryService, mock(EstimateValidationService.class),
            downloadUrls, noticeProvider, mock(EstimateNarrativeRepository.class),
            mock(EstimateNarrativeReader.class), new DetectionBoxReader(new ObjectMapper()));

    @BeforeEach
    void setUp() {
        ReportContextView context = mock(ReportContextView.class);
        given(context.getJobId()).willReturn(JOB_ID);
        given(context.getAccidentId()).willReturn(3L);
        given(context.getManufacturer()).willReturn("현대");
        given(context.getModelName()).willReturn("아반떼");
        given(context.getAccidentCreatedAt()).willReturn(Instant.parse("2026-09-18T04:35:00Z"));
        given(reportRepository.findContext(ESTIMATE_ID, MEMBER_ID)).willReturn(Optional.of(context));

        given(estimateQueryService.detail(ESTIMATE_ID, MEMBER_ID)).willReturn(estimate());
        given(estimateQueryService.basis(ESTIMATE_ID, MEMBER_ID))
                .willReturn(new EstimateBasisResponse(ESTIMATE_ID, (short) 1, List.of()));
        given(noticeProvider.legalNotice()).willReturn("참고용 추정치입니다.");
    }

    @Test
    @DisplayName("오버레이가 없으면 업로드한 사진을 싣고 파손 박스를 얹는다")
    void uploadedPhotoWithBoxes() {
        ReportImageView photo = view(null, "resized/1.jpg");
        given(reportRepository.findAnalyzedImages(JOB_ID)).willReturn(List.of(photo));
        given(downloadUrls.presign("resized/1.jpg")).willReturn(signed("https://bucket/resized-1"));

        Image image = onlyImage();

        assertThat(image.imageUrl()).isEqualTo("https://bucket/resized-1");
        assertThat(image.overlay()).isFalse();
        // 예전 필드는 뜻을 바꾸지 않는다 — 오버레이가 없으면 여전히 비어 있다
        assertThat(image.overlayUrl()).isNull();
        assertThat(image.boxes()).singleElement().satisfies(box -> {
            assertThat(box.left()).isEqualTo(10.0);
            assertThat(box.top()).isEqualTo(10.0);
            assertThat(box.width()).isEqualTo(40.0);
            assertThat(box.height()).isEqualTo(20.0);
            // 예상 수리비 표의 첫 행이 앞 범퍼다 — PDF 와 같은 번호다
            assertThat(box.number()).isEqualTo(1);
        });
    }

    /** 오버레이 그림에는 이미 파손 표시가 있다. 박스를 또 얹으면 어느 쪽이 AI 의 판단인지 모른다. */
    @Test
    @DisplayName("오버레이가 있으면 그것을 싣고 박스는 얹지 않는다")
    void overlayWinsWithoutBoxes() {
        ReportImageView withOverlay = view("overlay/1.jpg", "resized/1.jpg");
        given(reportRepository.findAnalyzedImages(JOB_ID)).willReturn(List.of(withOverlay));
        given(downloadUrls.presign("overlay/1.jpg")).willReturn(signed("https://bucket/overlay-1"));
        given(downloadUrls.presign("resized/1.jpg")).willReturn(signed("https://bucket/resized-1"));

        Image image = onlyImage();

        assertThat(image.imageUrl()).isEqualTo("https://bucket/overlay-1");
        assertThat(image.overlayUrl()).isEqualTo("https://bucket/overlay-1");
        assertThat(image.overlay()).isTrue();
        assertThat(image.boxes()).isEmpty();
    }

    /** 얹을 사진이 없으면 좌표가 있어도 박스를 만들지 않는다. 칸은 비우고 사진 수만 남긴다. */
    @Test
    @DisplayName("오버레이도 축소본도 없으면 사진 주소도 박스도 없다")
    void nothingToShow() {
        ReportImageView bare = view(null, null);
        given(reportRepository.findAnalyzedImages(JOB_ID)).willReturn(List.of(bare));

        Image image = onlyImage();

        assertThat(image.imageUrl()).isNull();
        assertThat(image.overlay()).isFalse();
        assertThat(image.boxes()).isEmpty();
    }

    /**
     * FE 가 받을 모양. {@code hasNumber()} 는 PDF 템플릿용이라 JSON 에 새면 안 된다 —
     * S15P21A307-537 때 {@code isEmpty()} 가 {@code "empty"} 로 새어 운영 데이터를 고친 적이 있다.
     */
    @Test
    @DisplayName("JSON 에는 imageUrl · overlay · boxes 가 나가고 hasNumber 는 새지 않는다")
    void jsonShape() {
        ReportImageView photo = view(null, "resized/1.jpg");
        given(reportRepository.findAnalyzedImages(JOB_ID)).willReturn(List.of(photo));
        given(downloadUrls.presign("resized/1.jpg")).willReturn(signed("https://bucket/resized-1"));

        JsonNode json = new ObjectMapper().valueToTree(onlyImage());

        assertThat(json.has("imageUrl")).isTrue();
        assertThat(json.has("overlay")).isTrue();
        assertThat(json.has("overlayUrl")).isTrue();
        JsonNode box = json.get("boxes").get(0);
        assertThat(box.has("number")).isTrue();
        assertThat(box.has("left")).isTrue();
        assertThat(box.has("hasNumber")).isFalse();
    }

    private Image onlyImage() {
        List<Image> images = service.report(ESTIMATE_ID, MEMBER_ID).images();
        assertThat(images).hasSize(1);
        return images.getFirst();
    }

    /** 목을 먼저 만든다 — given(...) 안에서 다른 목을 stub 하면 UnfinishedStubbingException 이다. */
    private static ReportImageView view(String overlayKey, String resizedKey) {
        ReportImageView view = mock(ReportImageView.class);
        given(view.getImageId()).willReturn(1L);
        given(view.getAngleCode()).willReturn("DAMAGE_CLOSE");
        given(view.getOverlayKey()).willReturn(overlayKey);
        given(view.getResizedKey()).willReturn(resizedKey);
        given(view.getDetections()).willReturn(DETECTIONS);
        given(view.getResizedWidth()).willReturn((short) 1000);
        given(view.getResizedHeight()).willReturn((short) 500);
        return view;
    }

    private static Optional<PresignedDownload> signed(String url) {
        return Optional.of(new PresignedDownload(URI.create(url), Instant.parse("2026-09-21T00:05:00Z")));
    }

    private static EstimateResponse estimate() {
        Instant now = Instant.parse("2026-09-18T04:35:00Z");
        List<EstimateItemResponse> items = List.of(
                new EstimateItemResponse(1L, "FRONT_BUMPER", "앞 범퍼", "FRONT", "Scratched",
                        "exchange", "교환", null, 87_900, 89_470, 33_200,
                        222_070, 222_070, 222_070, 3, false));
        return new EstimateResponse(ESTIMATE_ID, JOB_ID, (short) 1, true, null,
                null, null, 200_760, 222_070, 254_774, 3, "LOW", items, List.of(), List.of(), now);
    }
}
