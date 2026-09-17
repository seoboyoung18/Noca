package com.ssafy.a307.estimate.service;

import com.ssafy.a307.accident.image.AccidentImageDownloadUrls;
import com.ssafy.a307.accident.image.AccidentImageStoragePort.PresignedDownload;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimate.dto.EstimateBasisResponse;
import com.ssafy.a307.estimate.dto.EstimateReportResponse;
import com.ssafy.a307.estimate.dto.EstimateReportResponse.Accident;
import com.ssafy.a307.estimate.dto.EstimateReportResponse.Image;
import com.ssafy.a307.estimate.dto.EstimateReportResponse.Vehicle;
import com.ssafy.a307.estimate.dto.EstimateResponse;
import com.ssafy.a307.estimate.repository.EstimateReportRepository;
import com.ssafy.a307.estimate.repository.EstimateReportRepository.ReportContextView;
import com.ssafy.a307.estimate.repository.NativeTimestamps;
import com.ssafy.a307.estimatevalidation.dto.ValidationResultResponse;
import com.ssafy.a307.estimatevalidation.service.EstimateValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 사고 분석 견적 리포트 조립 (S15P21A307-337·338).
 *
 * <p><b>조립만 한다.</b> 견적·근거는 {@link EstimateQueryService}, 검증 결과는
 * {@link EstimateValidationService} 의 응답을 그대로 쓴다 — 같은 값을 두 번 계산하면 리포트와
 * 화면이 어긋날 수 있다. 소유자 검사도 그쪽과 같은 경로(견적 → 분석 → 사고 → 차량 → 회원)를 탄다.
 */
@Service
@RequiredArgsConstructor
public class EstimateReportService {

    private final EstimateReportRepository reportRepository;
    private final EstimateQueryService estimateQueryService;
    private final EstimateValidationService validationService;
    private final AccidentImageDownloadUrls downloadUrls;
    private final EstimateNoticeProvider noticeProvider;

    @Transactional(readOnly = true)
    public EstimateReportResponse report(Long estimateId, Long memberId) {
        ReportContextView context = reportRepository.findContext(estimateId, memberId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "존재하지 않는 견적입니다."));

        EstimateResponse estimate = estimateQueryService.detail(estimateId, memberId);
        EstimateBasisResponse basis = estimateQueryService.basis(estimateId, memberId);

        List<Image> images = reportRepository.findAnalyzedImages(context.getJobId()).stream()
                .map(image -> new Image(image.getImageId(), image.getAngleCode(),
                        downloadUrls.presign(image.getOverlayKey())
                                .map(PresignedDownload::url)
                                .map(Object::toString)
                                .orElse(null)))
                .toList();

        ValidationResultResponse validation = reportRepository
                .findLinkedValidationId(context.getAccidentId(), estimateId)
                .map(validationId -> validationService.result(memberId, validationId))
                .orElse(null);

        EstimateReportResponse report = new EstimateReportResponse(
                new Vehicle(context.getManufacturer(), context.getModelName(),
                        context.getVehicleType(), context.getCarClass(), context.getModelYear()),
                new Accident(context.getAccidentId(), NativeTimestamps.toInstant(context.getAccidentCreatedAt())),
                images,
                estimate,
                basis,
                validation,
                // 문구 출처가 상수에서 estimate_notice 테이블로 옮겨졌다(S15P21A307-288).
                // 값은 그대로다 — 이관이지 개정이 아니다. 비면 아래 requireSections 가 막는다.
                noticeProvider.legalNotice(),
                // 체크리스트·질문 안내 고지 (S15P21A307-487 · -488). 이쪽은 requireSections 가
                // 막지 않는다 — 실을 섹션이 아직 없어서 없다고 리포트를 죽일 이유가 없고,
                // 시드가 빠진 환경에서 리포트 전체가 500 이 되는 사고가 2026-09-14 에 있었다.
                noticeProvider.guidanceLimitNotice(),
                Instant.now());

        requireSections(report);
        return report;
    }

    /**
     * 필수 구성 항목 검사 (S15P21A307-338). 차량·사고·고지 문구가 없거나, 산정한 견적인데 파손
     * 목록이 비어 있으면 <b>리포트를 내보내지 않는다.</b>
     *
     * <p>사용자 입력이 아니라 저장된 데이터에서 조립한 것이라, 여기서 걸리면 데이터나 코드의
     * 결함이다. 빠진 채 나가면 사용자가 보험사·정비소에 불완전한 근거를 들고 가게 된다.
     *
     * <p>산정 불가 견적은 항목이 없어도 된다 — 금액 대신 사유를 싣는 것이 그 견적의 정상 모양이다.
     * 분석 이미지와 검증 결과도 필수가 아니다. 앞의 것은 칸마다 "분석 이미지 없음" 으로, 뒤의 것은
     * 섹션 생략으로 표현한다.
     */
    private static void requireSections(EstimateReportResponse report) {
        Vehicle vehicle = report.vehicle();
        if (isBlank(vehicle.manufacturer()) || isBlank(vehicle.modelName())) {
            throw new IllegalStateException("리포트 필수 항목 누락: 차량 정보");
        }
        if (report.accident().accidentId() == null || report.accident().createdAt() == null) {
            throw new IllegalStateException("리포트 필수 항목 누락: 사고 정보");
        }
        if (report.estimate().estimable() && report.estimate().items().isEmpty()) {
            throw new IllegalStateException("리포트 필수 항목 누락: 파손 목록");
        }
        if (isBlank(report.legalNotice())) {
            throw new IllegalStateException("리포트 필수 항목 누락: 고지 문구");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
