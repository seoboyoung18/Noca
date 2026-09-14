package com.ssafy.a307.analysis.callback;

import com.ssafy.a307.analysis.domain.AnalysisDamageType;
import com.ssafy.a307.analysis.domain.AnalysisRepairMethod;
import com.ssafy.a307.analysis.entity.AnalysisImageResult;
import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.entity.DamagedPart;
import com.ssafy.a307.analysis.repository.AnalysisImageResultRepository;
import com.ssafy.a307.analysis.repository.DamagedPartRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimate.domain.FallbackStage;
import com.ssafy.a307.estimate.domain.RefCondition;
import com.ssafy.a307.estimate.entity.ConfidenceGrade;
import com.ssafy.a307.estimate.entity.Estimate;
import com.ssafy.a307.estimate.entity.EstimateItem;
import com.ssafy.a307.estimate.repository.EstimateItemRepository;
import com.ssafy.a307.estimate.service.EstimateVersioningService;
import com.ssafy.a307.estimatevalidation.repository.PartCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * callback 이 실어 온 결과를 서비스 DB 에 옮긴다 (S15P21A307-157).
 *
 * <p>부르는 쪽({@link AnalysisCallbackService})이 이미 멱등성과 소유 관계를 확인했다. 여기서는
 * <b>계약을 DB 모양으로 바꾸는 일만</b> 한다. 같은 트랜잭션 안에서 돌므로 중간에 실패하면
 * 전부 되돌아간다 — 부분만 저장된 견적이 남는 것이 가장 나쁘다.
 *
 * <pre>
 *   imageResults[] → analysis_image_result   (estimable 여부와 무관)
 *   estimable=false → estimate(비산정) 한 행으로 끝
 *   items[]        → damaged_part → estimate → estimate_item
 * </pre>
 *
 * <p><b>순서가 정해져 있다.</b> {@code estimate_item.damaged_part_id} 가 NOT NULL FK 라
 * 부품이 먼저 있어야 하고, {@code estimate_id} 도 마찬가지다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisResultPersister {

    private final DamagedPartRepository damagedPartRepository;
    private final AnalysisImageResultRepository imageResultRepository;
    private final EstimateItemRepository estimateItemRepository;
    private final EstimateVersioningService versioningService;
    private final PartCodeRepository partCodeRepository;
    private final ObjectMapper objectMapper;

    /**
     * 저장한다.
     *
     * @param job 이미 조회된 작업. 상태 전이는 부르는 쪽이 한다
     */
    public void persist(AnalysisJob job, AnalysisCallbackRequest request) {
        saveImageResults(job, request);

        if (!Boolean.TRUE.equals(request.estimable())) {
            // 산정하지 못한 것도 행으로 남긴다. 없으면 화면이 "분석 중" 과
            // "분석은 끝났는데 산정이 안 됨" 을 구분하지 못한다.
            versioningService.appendNonEstimable(job.getJobId(), request.nonEstimableReason());
            log.info("산정 불가 견적 저장. jobId={} reason={}",
                    job.getJobId(), request.nonEstimableReason());
            return;
        }

        Map<String, DamagedPart> parts = saveDamagedParts(job, request.items());
        Estimate estimate = versioningService.append(
                job.getJobId(), amounts(request), grade(request.confidenceGrade()));
        saveItems(estimate, request, parts);
    }

    // ── 이미지 단위 결과 ─────────────────────────────────────────────────────

    /**
     * {@code estimable} 이 false 여도 저장한다 — 검출은 됐는데 사례가 부족해 산정만 못 한 경우,
     * 화면에 손상 부위는 보여 줄 수 있어야 한다(계약 "값 규칙").
     */
    private void saveImageResults(AnalysisJob job, AnalysisCallbackRequest request) {
        List<AnalysisImageResult> rows = new ArrayList<>();
        for (CallbackImageResult image : request.imageResults()) {
            rows.add(AnalysisImageResult.of(
                    job.getJobId(), image.imageId(),
                    // 원문을 그대로 직렬화한다. 자바 타입으로 바꾸지 않으므로 계약이 필드를
                    // 더해도 값이 사라지지 않는다.
                    image.detections() == null ? null : image.detections().toString(),
                    image.excluded(), image.exclusionReason()));
        }
        imageResultRepository.saveAll(rows);
    }

    // ── 부품 ────────────────────────────────────────────────────────────────

    /**
     * @return {@code partCode → DamagedPart}. 항목이 부품을 찾을 때 쓴다
     */
    private Map<String, DamagedPart> saveDamagedParts(AnalysisJob job, List<CallbackItem> items) {
        Map<String, DamagedPart> byPartCode = new LinkedHashMap<>();
        for (CallbackItem item : items) {
            String partCode = item.partCode().strip();

            // UNIQUE (job_id, part_code) 다. AI 가 같은 부품을 병합해 보내므로 정상 경로에서는
            // 겹치지 않지만, 겹치면 제약 위반으로 500 이 되기 전에 여기서 걸러 기록한다.
            if (byPartCode.containsKey(partCode)) {
                log.warn("같은 부품이 두 번 왔다 — 뒤엣것을 버린다. jobId={} partCode={}",
                        job.getJobId(), partCode);
                continue;
            }
            // FK 위반으로 500 이 나기 전에 끊는다. 표준 부품 코드가 아니면 계약 위반이다.
            if (!partCodeRepository.existsById(partCode)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "알 수 없는 부품 코드입니다: " + partCode);
            }
            byPartCode.put(partCode, DamagedPart.detected(
                    job, partCode,
                    AnalysisDamageType.from(item.damageType()),
                    repairMethod(item),
                    item.confidence()));
        }
        damagedPartRepository.saveAll(byPartCode.values());
        return byPartCode;
    }

    /**
     * 항목의 수리 방식.
     *
     * <p>{@code damaged_part.repair_method} 는 미정을 허용하도록 풀었지만(S15P21A307-155),
     * <b>견적 항목으로 온 이상 방식은 정해져 있어야 한다</b> — AI 가 이 방식으로 금액을 냈기
     * 때문이다. 없으면 그 금액의 근거를 되짚을 수 없으므로 계약 위반으로 끊는다.
     */
    private AnalysisRepairMethod repairMethod(CallbackItem item) {
        if (item.repairMethod() == null || item.repairMethod().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "금액이 있는 항목에는 repairMethod 가 필요합니다: " + item.partCode());
        }
        return AnalysisRepairMethod.from(item.repairMethod());
    }

    // ── 견적 ────────────────────────────────────────────────────────────────

    private void saveItems(Estimate estimate, AnalysisCallbackRequest request,
                           Map<String, DamagedPart> parts) {
        List<EstimateItem> rows = new ArrayList<>();
        for (CallbackItem item : request.items()) {
            DamagedPart part = parts.get(item.partCode().strip());
            if (part == null) {
                continue;   // 위에서 중복으로 버린 항목이다. 이미 로그를 남겼다
            }
            rows.add(EstimateItem.of(
                    estimate.getEstimateId(), part.getDamagedPartId(),
                    item.repairMethod(), item.standardHq(),
                    item.partCost(), item.laborCost(), item.paintMaterialCost(),
                    item.itemTotal(), item.refCaseCount(),
                    refCondition(item, request)));
        }
        estimateItemRepository.saveAll(rows);
        log.info("견적 저장. jobId={} estimateId={} version={} items={}",
                request.jobId(), estimate.getEstimateId(), estimate.getVersion(), rows.size());
    }

    /**
     * 총액.
     *
     * <p>{@code laborRate} 는 계약에 없어 비운다 — 공임 단가는 AI 가 쓴 값이고 우리에게 오지
     * 않는다. 근거 없는 값을 지어내지 않는다.
     *
     * <p>{@code totalHq} 는 항목의 {@code standardHq} 합이다. 계약이 총합을 보내지 않지만
     * 이것은 추측이 아니라 산술이다. 다만 <b>한 항목이라도 값이 없으면 비운다</b> — 일부만
     * 더한 합은 "총 표준작업시간" 이 아니면서 그렇게 보인다.
     */
    private Estimate.Amounts amounts(AnalysisCallbackRequest request) {
        CallbackTotals totals = request.totals();
        if (totals == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "estimable 이 true 이면 totals 가 필요합니다.");
        }
        return new Estimate.Amounts(null, totalHq(request.items()),
                totals.min(), totals.median(), totals.max(), request.refCaseTotal());
    }

    private BigDecimal totalHq(List<CallbackItem> items) {
        BigDecimal sum = BigDecimal.ZERO;
        for (CallbackItem item : items) {
            if (item.standardHq() == null) {
                return null;
            }
            sum = sum.add(item.standardHq());
        }
        return items.isEmpty() ? null : sum;
    }

    /** 모르는 등급은 비운다. {@code ck_est_grade} 가 세 값만 허용하고 NULL 도 받는다. */
    private ConfidenceGrade grade(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ConfidenceGrade.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("알 수 없는 confidenceGrade 를 비운다: {}", value);
            return null;
        }
    }

    /**
     * 항목별 근거 스냅샷 ({@code estimate_item.ref_condition}).
     *
     * <p>{@code refYearFrom}·{@code refYearTo} 는 계약에서 <b>견적 레벨</b>에 오는데 근거는
     * 항목 단위다. 항목마다 같은 값을 복사한다 — 화면이 항목을 펼칠 때마다 "몇 년도 사례를
     * 봤나" 를 보여 줘야 하고, 견적 응답을 한 번 더 부르게 만들 이유가 없다.
     *
     * <p>직렬화에 실패해도 저장을 멈추지 않는다. 근거 한 줄 때문에 금액을 통째로 잃는 것보다
     * 근거가 비어 있는 편이 낫다 — 읽는 쪽({@code RefConditionReader})이 같은 판단을 한다.
     */
    private String refCondition(CallbackItem item, AnalysisCallbackRequest request) {
        RefCondition condition = new RefCondition(
                fallbackStage(item.fallbackStage()),
                item.costDistribution() == null ? null : new RefCondition.CostDistribution(
                        item.costDistribution().p25(),
                        item.costDistribution().median(),
                        item.costDistribution().p75()),
                request.refYearFrom(), request.refYearTo(),
                item.repairMethodReason() == null ? null : new RefCondition.RepairMethodReason(
                        item.repairMethodReason().candidates(),
                        item.repairMethodReason().reasonCode()),
                item.referencedCaseIds());
        try {
            return objectMapper.writeValueAsString(condition);
        } catch (RuntimeException e) {
            log.warn("산정 근거를 직렬화하지 못해 비운다. partCode={}", item.partCode(), e);
            return EstimateItem.EMPTY_REF_CONDITION;
        }
    }

    private FallbackStage fallbackStage(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return FallbackStage.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("알 수 없는 fallbackStage 를 비운다: {}", value);
            return null;
        }
    }
}
