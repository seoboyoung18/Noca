package com.ssafy.a307.estimate.pdf;

import com.ssafy.a307.accident.image.AccidentImageStoragePort;
import com.ssafy.a307.estimate.dto.EstimateItemResponse;
import com.ssafy.a307.estimate.dto.EstimateReportResponse;
import com.ssafy.a307.estimate.pdf.EstimatePdfRepository.JobView;
import com.ssafy.a307.estimate.pdf.EstimatePdfStoragePort.StoredPdf;
import com.ssafy.a307.estimate.repository.EstimateReportRepository;
import com.ssafy.a307.estimate.repository.EstimateReportRepository.ReportContextView;
import com.ssafy.a307.estimate.repository.EstimateReportRepository.ReportImageView;
import com.ssafy.a307.estimate.service.EstimateReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PDF <b>한 건</b>의 생성 — 리포트 조립 → 오버레이 읽기 → HTML → PDF → 저장 → 완료 (S15P21A307-392).
 *
 * <p><b>워커와 별개 빈인 이유는 트랜잭션 경계다</b>({@code ValidationPdfProcessor} 와 같다). 건마다
 * {@code REQUIRES_NEW} 로 끊어야 한 건의 실패가 같은 주기의 다른 건을 오염시키지 않는데, 자기 호출은
 * 프록시를 거치지 않아 그 설정이 먹지 않는다. 실패 기록도 별개 트랜잭션이다 — 실패한 트랜잭션은
 * 롤백되므로 같은 곳에 쓰면 기록까지 사라진다.
 *
 * <p><b>오버레이를 읽지 못하면 "분석 이미지 없음" 으로 넘기지 않고 실패한다.</b> 한 번 만든 PDF 는
 * 번호가 붙어 굳는다 — 일시적인 저장소 오류로 사진이 빠진 문서를 남기느니 재시도한다.
 * 오버레이 키가 애초에 없는 사진만 "없음" 이다.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "app.estimate-pdf", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
public class EstimatePdfProcessor {

    /** {@code ck_er_retry CHECK (retry_count BETWEEN 0 AND 3)}. */
    public static final short MAX_RETRY_COUNT = 3;

    /** {@code failure_reason VARCHAR(200)}. 넘치면 기록조차 실패한다. */
    static final int MAX_FAILURE_REASON_LENGTH = 200;

    /**
     * PDF 에 실을 사진 수 상한 (S15P21A307-547).
     *
     * <p>사고당 20장까지 올릴 수 있는데 전부 실으면 base64 로 4MB 가까이 된다. 축소본이
     * 평균 144KB 라 8장이면 1.5MB 선에서 멈춘다. 넘는 사진은 칸을 만들지 않고 아래에
     * "외 N장" 으로 알린다 — 조용히 빼면 사용자는 자기 사진이 분석에 안 쓰인 줄 안다.
     */
    static final int MAX_IMAGES_IN_PDF = 8;

    /**
     * 사진 틀의 최대 크기. <b>템플릿의 {@code .image-cell img} 와 같은 값이어야 한다</b> —
     * 둘이 어긋나면 사진은 CSS 대로 줄어드는데 박스는 여기 값으로 자리를 잡아 밀린다.
     * 폭은 A4 본문 180mm 에서 표 안쪽 여백을 뺀 값이다.
     */
    private static final double MAX_PHOTO_WIDTH_MM = 165;

    private static final double MAX_PHOTO_HEIGHT_MM = 95;

    private final EstimatePdfRepository pdfRepository;
    private final EstimateReportRepository reportRepository;
    private final EstimateReportService reportService;
    private final EstimatePdfGenerator generator;
    private final DetectionBoxReader boxReader;
    private final Optional<EstimatePdfStoragePort> pdfStorage;
    private final Optional<AccidentImageStoragePort> imageStorage;

    /** @return 내가 선점했으면 true */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long reportId) {
        return pdfRepository.claim(reportId, MAX_RETRY_COUNT) == 1;
    }

    /**
     * @throws RuntimeException 이 건만 실패. 워커가 받아 {@link #markFailed} 로 기록한다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long reportId) {
        // PDF 보관소부터 본다 (S15P21A307-528). 아래의 리포트 조립·오버레이 읽기·렌더링은 저장할 곳이
        // 있어야 쓸모가 있다 — 순서가 거꾸로면 다 만든 PDF 를 버린다. 건의 내용과 무관한 설정 문제라
        // 리포트 행을 읽기 전에 확인한다 (ValidationPdfProcessor 가 S15P21A307-526 에서 같은 모양이다).
        //
        // ★ 사고 이미지 저장소(imageStorage)는 여기서 보지 않는다. 오버레이 키가 있는 사진을 읽을 때만
        //   필요하고(overlayDataUri), 키가 없는 리포트는 그 저장소 없이도 만들어진다. 오버레이는
        //   2026-09-11 에 폐기돼 키가 늘 NULL 이라, 여기서 함께 확인하면 staging 버킷이 없는 환경의
        //   견적 PDF 가 전부 새로 실패한다.
        EstimatePdfStoragePort storage = pdfStorage
                .orElseThrow(() -> new MissingStorageException("PDF 보관소가 구성되지 않았다: " + reportId));

        JobView job = pdfRepository.findJob(reportId)
                .orElseThrow(() -> new IllegalStateException("선점한 리포트가 사라졌다: " + reportId));
        // 요청자가 없는 내부 경로라 소유자를 읽어 같은 조립 경로를 탄다.
        Long ownerId = pdfRepository.findOwnerMemberId(job.getEstimateId())
                .orElseThrow(() -> new IllegalStateException("견적이 사라졌다: " + job.getEstimateId()));

        EstimateReportResponse report = reportService.report(job.getEstimateId(), ownerId);
        Map<Long, ImageSource> sources = imageSources(job.getEstimateId(), ownerId);
        // 박스 번호는 예상 수리비 표의 순번과 같아야 한다 - 표의 부위와 사진의 번호가
        // 이어지지 않으면 번호는 장식일 뿐이다.
        Map<String, Integer> partNumbers = partNumbers(report);
        List<EstimatePdfDocument.Image> images = report.images().stream()
                .limit(MAX_IMAGES_IN_PDF)
                .map(image -> toPdfImage(image.imageId(), image.angleCode(),
                        sources.get(image.imageId()), partNumbers))
                .toList();

        byte[] pdf = generator.generate(
                new EstimatePdfDocument(job.getReportNo(), report, images, Instant.now()));

        StoredPdf stored = storage.store(job.getReportNo(), pdf);

        if (pdfRepository.complete(reportId, stored.storageKey()) != 1) {
            throw new IllegalStateException("처리 중이 아닌 리포트를 완료로 옮기려 했다: " + reportId);
        }
        log.info("견적 PDF 완료: reportId={}, reportNo={}, {}bytes", reportId, job.getReportNo(), stored.size());
    }

    /**
     * 실패를 기록한다. 재시도 여지가 있으면 큐로 되돌리고, 상한이면 종결한다.
     * {@code reason} 은 사용자에게 보여도 되는 분류 문구여야 한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long reportId, String reason) {
        if (pdfRepository.returnToQueue(reportId, MAX_RETRY_COUNT) == 1) {
            log.warn("견적 PDF 생성 실패 — 큐로 되돌린다: reportId={}", reportId);
            return;
        }
        if (pdfRepository.fail(reportId, truncate(reason)) == 1) {
            log.error("견적 PDF 생성 실패 — 재시도 상한에 도달해 종결한다: reportId={}", reportId);
        }
    }

    /**
     * 큐로 되돌리지 않고 바로 실패로 끝낸다. <b>다시 시도해도 결과가 같은 실패</b>에만 쓴다
     * (S15P21A307-528, {@code ValidationPdfProcessor.markFailedWithoutRetry} 와 같은 판단).
     *
     * <p>지금은 저장소 미구성({@link MissingStorageException}) 하나뿐이고, <b>두 저장소를 나누지 않았다.</b>
     * PDF 보관소({@code S3EstimatePdfStorage})와 사고 이미지 저장소({@code S3AccidentImageStorage})는
     * 둘 다 버킷 프로퍼티를 보는 {@code @ConditionalOnExpression} 빈이고 {@code Optional} 로 기동할 때
     * 한 번 주입된다 — 재기동 전에는 없던 것이 생기지 않는다. 이미지 저장소 쪽 실패는 오버레이 키가 있는
     * 리포트에서만 나는데, 키는 그 견적의 분석 작업({@code job_id})에서 읽으므로 같은 건을 다시 집어도
     * 같은 키로 같은 곳에서 걸린다. 정책이 같으니 예외 타입을 둘로 나눌 이유가 없다 — 어느 저장소인지는
     * 예외 메시지가 로그에 남긴다.
     *
     * <p>{@link #markFailed} 는 그대로 두었다. 렌더링 실패·오버레이 읽기 실패·고아 회수는 일시적일 수
     * 있어 지금처럼 재시도한다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailedWithoutRetry(Long reportId, String reason) {
        if (pdfRepository.fail(reportId, truncate(reason)) == 1) {
            log.error("견적 PDF 생성 실패 — 다시 시도해도 같아 바로 종결한다: reportId={}", reportId);
        }
    }

    /** 상한에 닿은 채 큐에 남아 아무도 집을 수 없는 건을 종결한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void abandonExhausted(Long reportId, String reason) {
        if (pdfRepository.abandon(reportId, MAX_RETRY_COUNT, truncate(reason)) == 1) {
            log.error("견적 PDF 재시도 상한 도달 — 큐에서 제거한다: reportId={}", reportId);
        }
    }

    /**
     * 사진 한 장에 딸린 것. 리포트 응답은 키를 담지 않으므로(서명 URL 만) 같은 쿼리로 다시 읽는다.
     *
     * @param detections 파손 위치 좌표 원문. {@code width}·{@code height} 와 기준이 같다
     */
    private record ImageSource(String overlayKey, String resizedKey, String detections,
                               Short width, Short height) {
    }

    private Map<Long, ImageSource> imageSources(Long estimateId, Long ownerId) {
        ReportContextView context = reportRepository.findContext(estimateId, ownerId)
                .orElseThrow(() -> new IllegalStateException("리포트 문맥이 사라졌다: " + estimateId));
        Map<Long, ImageSource> sources = new HashMap<>();
        for (ReportImageView image : reportRepository.findAnalyzedImages(context.getJobId())) {
            sources.put(image.getImageId(), new ImageSource(
                    image.getOverlayKey(), image.getResizedKey(), image.getDetections(),
                    image.getResizedWidth(), image.getResizedHeight()));
        }
        return sources;
    }

    /**
     * 부위 코드 → 예상 수리비 표의 순번 (S15P21A307-547).
     *
     * <p>같은 부위가 두 행에 있으면 <b>먼저 나온 행</b>의 번호를 쓴다. 번호는 행마다 하나씩
     * 올라가므로 템플릿의 {@code stat.count} 와 어긋나지 않는다.
     */
    private static Map<String, Integer> partNumbers(EstimateReportResponse report) {
        Map<String, Integer> numbers = new HashMap<>();
        int number = 1;
        for (EstimateItemResponse item : report.estimate().items()) {
            if (item.partCode() != null) {
                numbers.putIfAbsent(item.partCode(), number);
            }
            number++;
        }
        return numbers;
    }

    /**
     * 실을 사진을 고른다 (S15P21A307-547).
     *
     * <p><b>오버레이가 있으면 그것을, 없으면 사용자가 올린 축소본을 싣는다.</b> 오버레이는
     * 2026-09-11 에 폐기돼 키가 늘 비어 있어, 이 대체가 없으면 리포트의 사진 칸이 통째로
     * "분석 이미지 없음" 으로 남는다 — 파손 사진 없는 견적 리포트는 근거를 보여 주지 못한다.
     *
     * <p>둘 다 없으면 {@code null} 이고 템플릿이 그 칸을 비운다. 칸 자체를 없애지 않는 것은
     * 분석에 쓰인 사진 수를 사용자가 알 수 있어야 하기 때문이다.
     *
     * <p><b>오버레이에는 박스를 얹지 않는다.</b> 그 그림에 이미 파손 표시가 들어 있어,
     * 두 표시가 겹치면 어느 쪽이 AI 의 판단인지 알 수 없다.
     */
    private EstimatePdfDocument.Image toPdfImage(Long imageId, String angleCode,
                                                 ImageSource source,
                                                 Map<String, Integer> partNumbers) {
        if (source == null) {
            return new EstimatePdfDocument.Image(imageId, angleCode, null, false);
        }
        String overlay = readDataUri(source.overlayKey());
        if (overlay != null) {
            return new EstimatePdfDocument.Image(imageId, angleCode, overlay, true);
        }
        return new EstimatePdfDocument.Image(imageId, angleCode,
                readDataUri(source.resizedKey()), false,
                frame(source.width(), source.height()),
                boxReader.read(source.detections(), source.width(), source.height(), partNumbers));
    }

    /**
     * 원본 비율을 지키면서 지면에 들어갈 크기로 (S15P21A307-547).
     *
     * <p>치수를 모르면 {@code null} 이고, 템플릿이 예전처럼 {@code max-width}·
     * {@code max-height} 로만 그린다 — 그때는 박스도 없으므로 틀이 필요 없다.
     */
    private static EstimatePdfDocument.Frame frame(Short width, Short height) {
        if (width == null || height == null || width <= 0 || height <= 0) {
            return null;
        }
        double ratio = (double) height / width;
        double frameWidth = MAX_PHOTO_WIDTH_MM;
        double frameHeight = frameWidth * ratio;
        if (frameHeight > MAX_PHOTO_HEIGHT_MM) {
            frameHeight = MAX_PHOTO_HEIGHT_MM;
            frameWidth = frameHeight / ratio;
        }
        return new EstimatePdfDocument.Frame(round(frameWidth), round(frameHeight));
    }

    /** 소수 둘째 자리까지. 인쇄물에서 그 아래는 보이지 않는다. */
    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private String readDataUri(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        byte[] bytes = imageStorage
                .orElseThrow(() -> new MissingStorageException("사고 이미지 저장소가 없어 사진을 읽을 수 없다"))
                .read(key);
        return toDataUri(bytes);
    }

    /**
     * 이미지 바이트를 HTML 에 박을 수 있는 data URI 로. 형식은 파일 첫 바이트로 판단한다 —
     * 오버레이 형식은 AI 계약(157) 전이라 JPEG·PNG 만 받고, 모르는 형식은 조용히 넘기지 않는다.
     */
    static String toDataUri(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            throw new IllegalStateException("오버레이 이미지가 비어 있다");
        }
        String type;
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            type = "image/jpeg";
        } else if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            type = "image/png";
        } else {
            throw new IllegalStateException("지원하지 않는 오버레이 이미지 형식");
        }
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String stripped = reason.strip();
        return stripped.length() > MAX_FAILURE_REASON_LENGTH
                ? stripped.substring(0, MAX_FAILURE_REASON_LENGTH) : stripped;
    }

    /**
     * 설정 문제라 다시 시도해도 같다 — 사용자에게 다른 사유를 보이고, 재시도하지 않는다
     * ({@link #markFailedWithoutRetry}).
     */
    static class MissingStorageException extends IllegalStateException {
        MissingStorageException(String message) {
            super(message);
        }
    }
}
