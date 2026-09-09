package com.ssafy.a307.estimatevalidation.service;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.estimatevalidation.domain.ValidationStatus;
import com.ssafy.a307.estimatevalidation.dto.CostComparisonResponse;
import com.ssafy.a307.estimatevalidation.dto.ModelImprovementRecord;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidation;
import com.ssafy.a307.estimatevalidation.repository.EstimateReadRepository;
import com.ssafy.a307.estimatevalidation.repository.EstimateValidationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CostComparisonService {

    private static final int MAX_EXPORT_SIZE = 1000;

    private final EstimateValidationService validationService;
    private final EstimateValidationRepository validationRepository;
    private final EstimateReadRepository estimateReadRepository;

    @Transactional(readOnly = true)
    public CostComparisonResponse compare(Long memberId, Long accidentId) {
        Accident accident = validationService.ownedAccident(memberId, accidentId);
        Integer actual = accident.getActualRepairCost();
        EstimateReadRepository.LatestEstimateView ai = estimateReadRepository
                .findLatestCompletedForAccident(accidentId).orElse(null);
        List<EstimateValidation> validations = validationRepository
                .findCompletedByAccident(accidentId, memberId, ValidationStatus.COMPLETED);

        CostComparisonResponse.AiEstimate aiResponse = ai == null ? null : new CostComparisonResponse.AiEstimate(
                ai.getEstimateId(), ai.getVersion(), ai.getTotalMin(), ai.getTotalMedian(), ai.getTotalMax(),
                difference(ai.getTotalMedian(), actual), within(actual, ai.getTotalMin(), ai.getTotalMax()));
        List<CostComparisonResponse.ShopEstimate> shops = validations.stream()
                .map(validation -> new CostComparisonResponse.ShopEstimate(
                        validation.getValidationId(), validation.getClaimedTotal(),
                        difference(validation.getClaimedTotal(), actual), validation.getCompletedAt()))
                .toList();
        return new CostComparisonResponse(
                accidentId, aiResponse, shops,
                new CostComparisonResponse.ActualRepair(
                        actual, accident.getActualRepairCompletedDate(), accident.getRepairShopName(),
                        accident.getActualCostRecordedAt()));
    }

    /**
     * Cursor key is accident_id. recordKey(accidentId:validationId|none) is stable, so a downstream
     * upsert replaces actual-cost corrections instead of accumulating duplicates. No member/file key is exposed.
     */
    @Transactional(readOnly = true)
    public List<ModelImprovementRecord> export(long afterAccidentId, int requestedSize) {
        if (afterAccidentId < 0 || requestedSize < 1) {
            throw new IllegalArgumentException("afterAccidentId and size must be positive");
        }
        int size = Math.min(requestedSize, MAX_EXPORT_SIZE);
        return validationRepository.findModelImprovementRecords(
                        afterAccidentId, PageRequest.of(0, size))
                .stream()
                .map(row -> new ModelImprovementRecord(
                        row.getRecordKey(), row.getAccidentId(), row.getCarClass(), row.getEstimateId(),
                        row.getEstimateVersion(), row.getAiTotalMin(), row.getAiTotalMedian(), row.getAiTotalMax(),
                        row.getValidationId(), row.getShopClaimedTotal(), row.getActualRepairCost(),
                        row.getActualRepairCompletedDate(),
                        row.getActualCostRecordedAt() == null ? null : row.getActualCostRecordedAt().toInstant()))
                .toList();
    }

    private static Long difference(Integer left, Integer right) {
        return left == null || right == null ? null : (long) left - right;
    }

    private static Boolean within(Integer value, Integer min, Integer max) {
        return value == null || min == null || max == null ? null : value >= min && value <= max;
    }
}
