package com.ssafy.a307.accident.service;

import com.ssafy.a307.accident.dto.AccidentCreateRequest;
import com.ssafy.a307.accident.dto.AccidentHistoryStatus;
import com.ssafy.a307.accident.dto.AccidentSummaryResponse;
import com.ssafy.a307.accident.dto.AccidentPageResponse;
import com.ssafy.a307.accident.dto.AccidentResponse;
import com.ssafy.a307.accident.dto.DirectVehicleInput;
import com.ssafy.a307.accident.dto.ActualRepairCostRequest;
import com.ssafy.a307.accident.dto.ActualRepairCostResponse;
import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.accident.entity.VehicleInputType;
import com.ssafy.a307.accident.image.AccidentImageDownloadUrls;
import com.ssafy.a307.accident.image.AccidentImageStoragePort;
import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.analysis.entity.AnalysisJobStatus;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.vehicle.entity.Vehicle;
import com.ssafy.a307.vehicle.repository.VehicleRepository;
import com.ssafy.a307.vehicle.service.VehicleRegistrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 차량 도메인과 같은 방식이다 — memberId 를 첫 파라미터로 받고,
 * 소유자 검사는 Repository 쿼리 조건에 넣는다.
 */
@Service
@RequiredArgsConstructor
public class AccidentService {

    private final AccidentRepository accidentRepository;
    private final VehicleRepository vehicleRepository;
    private final VehicleRegistrationService vehicleRegistrationService;
    private final AccidentImageDownloadUrls downloadUrls;

    /**
     * 같은 차량으로 사고를 여러 건 접수할 수 있다. 막을 이유가 없어 검사하지 않는다.
     * 남의 차량·없는 차량·삭제된 차량은 모두 404 — 403 은 그 차량이 존재한다는 사실을 알려준다.
     */
    @Transactional
    public AccidentResponse create(Long memberId, AccidentCreateRequest request) {
        if (!request.isVehicleInputValid()) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "vehicleId와 directVehicle 중 정확히 하나가 필요합니다.");
        }

        Vehicle vehicle;
        VehicleInputType inputType;
        if (request.vehicleId() != null) {
            vehicle = vehicleRepository
                    .findActiveByVehicleIdAndMemberId(request.vehicleId(), memberId)
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.NOT_FOUND, "차량을 찾을 수 없습니다."));
            inputType = VehicleInputType.REGISTERED;
        } else {
            DirectVehicleInput direct = request.directVehicle();
            vehicle = vehicleRegistrationService.registerByExactModel(
                    memberId, direct.manufacturer(), direct.modelName(), direct.modelYear());
            inputType = VehicleInputType.DIRECT;
        }

        return AccidentResponse.from(accidentRepository.save(Accident.open(vehicle, inputType)));
    }

    /**
     * 없는 사고·남의 사고·다른 회원 차량의 사고를 구분하지 않고 전부 404 다 —
     * 403 은 그 사고가 존재한다는 사실을 알려준다. {@code create} 의 차량 조회와 같은 판단이다.
     *
     * <p>소유자 검사는 Repository 쿼리 조건에 있고, 차량이 소프트 삭제되어도
     * 소유자에게서 사고를 숨기지 않는다. 차량 필드는 접수 당시 스냅샷이다.
     */
    @Transactional(readOnly = true)
    public AccidentResponse findOne(Long memberId, Long accidentId) {
        return accidentRepository.findByAccidentIdAndMemberId(accidentId, memberId)
                .map(AccidentResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다."));
    }

    /** 사고가 한 건도 없으면 404 가 아니라 빈 목록이다. 폐차한 차량의 사고도 남는다. */
    @Transactional(readOnly = true)
    public List<AccidentResponse> findMine(Long memberId) {
        return accidentRepository.findAllByMemberId(memberId).stream()
                .map(AccidentResponse::from)
                .toList();
    }

    /**
     * 접수 시각은 {@code TIMESTAMPTZ}({@link Instant})이고 수리 완료일은 날짜뿐이라, 둘을 비교하려면
     * 기준 시간대가 있어야 한다. 서비스 사용자와 정비소가 국내에 있으므로 한국 시간을 기준으로 본다.
     * UTC 로 비교하면 한국 시각 오전 9시 이전에 접수된 건의 접수일이 하루 앞으로 밀려,
     * 접수 당일 수리 완료가 잘못 거절된다.
     */
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    /** 페이지 파라미터 기본값. 명세서에 값이 없어 잠정치이며 컨트롤러와 이 상수만 고치면 된다. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /** 한 번에 내보낼 수 있는 상한. 초과 요청은 거절하지 않고 이 값으로 줄인다. */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * 페이지 단위 이력 조회.
     *
     * <p>범위를 넘는 {@code page} 는 404 가 아니라 <b>빈 목록 + 200</b> 이다. 목록 API 가
     * 비어 있는 것은 오류가 아니라는 {@link #findMine} 의 판단을 그대로 따른다.
     *
     * <p>음수 {@code page} 와 1 미만 {@code size} 는 400 이 아니라 각각 0·기본값으로 보정한다.
     * 잘못된 페이지 파라미터로 화면이 깨지는 것보다 첫 페이지를 보여주는 편이 낫고,
     * 명세서가 이 경우의 오류 코드를 정하지 않았다.
     */
    @Transactional(readOnly = true)
    public AccidentPageResponse findMinePaged(Long memberId, Integer page, Integer size) {
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null || size < 1 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Page<AccidentSummaryResponse> found =
                accidentRepository.findPageByMemberId(memberId, PageRequest.of(safePage, safeSize));
        return AccidentPageResponse.of(enrich(found.getContent()), found);
    }

    /**
     * 목록 전용 값(썸네일·상태·견적)을 채운다(Task 225 · prompt58).
     *
     * <p><b>페이지 크기와 무관하게 추가 쿼리가 3개다.</b> 건마다 조회하면 20건 페이지에 60번이
     * 더 나간다. 페이지를 먼저 자르고 그 id 들로만 한 번씩 모아 온다.
     *
     * <p>분석 작업 상태가 추가됐지만 <b>쿼리 수는 그대로 3개다.</b> 견적 보완 쿼리가 이미
     * {@code analysis_job} 을 지나가므로 거기에 합류시켰다
     * ({@code AccidentRepository#findAnalysisByAccidentIds}).
     *
     * <p>썸네일 URL 서명은 순수 계산이라 네트워크를 타지 않는다 — 20건이면 20번 서명하지만
     * 왕복은 0이다. 오브젝트가 실제로 있는지는 확인하지 않는다. 확인하려면 건마다
     * {@code HeadObject} 왕복이 생기고, 없는 키는 브라우저가 열 때 404 로 나 이미지 한 칸이
     * 비는 것으로 끝난다.
     */
    private List<AccidentSummaryResponse> enrich(List<AccidentSummaryResponse> page) {
        if (page.isEmpty()) {
            return page;
        }
        List<Long> ids = page.stream().map(AccidentSummaryResponse::accidentId).toList();

        Map<Long, Long> imageCounts = new HashMap<>();
        accidentRepository.countImagesByAccidentIds(ids)
                .forEach(v -> imageCounts.put(v.getAccidentId(), v.getImageCount()));

        Map<Long, String> thumbnailKeys = new HashMap<>();
        accidentRepository.findThumbnailKeysByAccidentIds(ids)
                .forEach(v -> thumbnailKeys.put(v.getAccidentId(), v.getS3Key()));

        Map<Long, AccidentRepository.AccidentAnalysisView> analyses = new HashMap<>();
        accidentRepository.findAnalysisByAccidentIds(ids)
                .forEach(v -> analyses.put(v.getAccidentId(), v));

        return page.stream()
                .map(summary -> withDetails(summary, imageCounts, thumbnailKeys, analyses))
                .toList();
    }

    private AccidentSummaryResponse withDetails(
            AccidentSummaryResponse summary,
            Map<Long, Long> imageCounts,
            Map<Long, String> thumbnailKeys,
            Map<Long, AccidentRepository.AccidentAnalysisView> analyses) {

        Long accidentId = summary.accidentId();
        int imageCount = imageCounts.getOrDefault(accidentId, 0L).intValue();
        AccidentRepository.AccidentAnalysisView analysis = analyses.get(accidentId);

        Optional<AccidentImageStoragePort.PresignedDownload> thumbnail =
                downloadUrls.presign(thumbnailKeys.get(accidentId));

        return summary.withDetails(
                statusOf(imageCount, analysis),
                imageCount,
                thumbnail.map(d -> d.url().toString()).orElse(null),
                thumbnail.map(AccidentImageStoragePort.PresignedDownload::expiresAt).orElse(null),
                analysis == null ? null : analysis.getEstimateId(),
                analysis == null ? null : analysis.getTotalMin(),
                analysis == null ? null : analysis.getTotalMedian(),
                analysis == null ? null : analysis.getTotalMax());
    }

    /**
     * 상태 유도 규칙. {@link AccidentHistoryStatus} 가 저장 컬럼이 아니라 유도값이라 규칙을
     * <b>여기 한 곳에만</b> 둔다 — 기획이 상태 축을 확정하면 이 메서드만 바꾼다.
     *
     * <p>우선순위와 그 근거는 {@link AccidentHistoryStatus} javadoc 에 있다. 요약하면
     * <b>실패 → 진행 중 → 견적 → 이미지 → 접수</b> 이고, 분석 상태가 견적을 이기는 이유는
     * 재분석의 실패·진행이 예전 견적에 가려지면 사용자가 지금 보는 금액이 최신인지 알 수 없기
     * 때문이다.
     *
     * <p>재분석으로 작업이 여러 개면 <b>가장 최근 작업</b>만 본다. 어느 것이 최근인지는 쿼리가
     * 정하고({@code created_at desc, job_id desc}) 진행 상태 API 와 같은 정렬이다.
     *
     * <p>{@code analysis_job} 이 없으면 예전과 똑같이 이미지 장수만 본다 — 기존 4값의 의미를
     * 바꾸지 않았다.
     *
     * <p>실제 수리비 기록 여부는 이미 읽어 온 페이지에 없어 별도로 보지 않는다.
     * {@code actual_repair_cost} 를 투영에 넣지 않은 이유가 있다 — 그 값은 금액이라 목록에
     * 노출할지 기획 결정이 필요하다. 그래서 {@link AccidentHistoryStatus#REPAIR_RECORDED} 는
     * 아직 유도하지 않는다.
     *
     * @param analysis 최신 분석 작업 + 최신 견적. 분석을 요청한 적이 없으면 {@code null}
     */
    private AccidentHistoryStatus statusOf(
            int imageCount, AccidentRepository.AccidentAnalysisView analysis) {

        if (analysis != null) {
            AnalysisJobStatus jobStatus = AnalysisJobStatus.valueOf(analysis.getJobStatus());
            if (jobStatus == AnalysisJobStatus.FAILED) {
                return AccidentHistoryStatus.ANALYSIS_FAILED;
            }
            if (jobStatus == AnalysisJobStatus.QUEUED || jobStatus == AnalysisJobStatus.PROCESSING) {
                return AccidentHistoryStatus.ANALYZING;
            }
            if (analysis.getEstimateId() != null) {
                return AccidentHistoryStatus.ESTIMATED;
            }
        }
        return imageCount > 0 ? AccidentHistoryStatus.IMAGES_UPLOADED : AccidentHistoryStatus.RECEIVED;
    }

    @Transactional
    public ActualRepairCostResponse recordActualRepairCost(
            Long memberId, Long accidentId, ActualRepairCostRequest request) {
        Accident accident = accidentRepository.findByAccidentIdAndMemberId(accidentId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다."));

        requireNotBeforeReport(accident, request.repairCompletedDate());

        accident.recordActualRepair(
                request.actualRepairCost(),
                request.repairCompletedDate(),
                request.repairShopName().strip(),
                Instant.now());
        return ActualRepairCostResponse.from(accident);
    }

    /**
     * 수리 완료일이 사고 접수일보다 이전이면 거절한다.
     *
     * <p>DTO 애너테이션으로는 막을 수 없다 — {@code @PastOrPresent} 는 오늘을 기준으로 볼 뿐
     * 이 사고가 언제 접수됐는지 모른다. 정본 DDL 에도 두 열을 비교하는 CHECK 가 없다
     * ({@code ck_ac_cost} 는 금액만 본다). 그래서 접수 시각을 아는 이 지점에서 본다.
     *
     * <p><b>같은 날은 허용한다.</b> 접수 당일에 수리가 끝나는 일이 실제로 있다.
     */
    private void requireNotBeforeReport(Accident accident, LocalDate repairCompletedDate) {
        LocalDate reportedOn = LocalDate.ofInstant(accident.getCreatedAt(), SERVICE_ZONE);
        if (repairCompletedDate.isBefore(reportedOn)) {
            throw new BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "수리 완료일은 사고 접수일(%s)보다 이전일 수 없습니다. (입력 %s)"
                            .formatted(reportedOn, repairCompletedDate));
        }
    }
}
