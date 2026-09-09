package com.ssafy.a307.estimatevalidation.service;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimatevalidation.config.EstimateValidationProperties;
import com.ssafy.a307.estimatevalidation.domain.AnalysisSnapshot;
import com.ssafy.a307.estimatevalidation.domain.CostReference;
import com.ssafy.a307.estimatevalidation.domain.EstimateLine;
import com.ssafy.a307.estimatevalidation.domain.EstimateValidationEngine;
import com.ssafy.a307.estimatevalidation.domain.GeneratedQuestion;
import com.ssafy.a307.estimatevalidation.domain.GradeAssessment;
import com.ssafy.a307.estimatevalidation.domain.GradeDecider;
import com.ssafy.a307.estimatevalidation.domain.QuestionSource;
import com.ssafy.a307.estimatevalidation.domain.RepairShopQuestionGenerator;
import com.ssafy.a307.estimatevalidation.domain.StandardRepairMethod;
import com.ssafy.a307.estimatevalidation.domain.ValidatedLine;
import com.ssafy.a307.estimatevalidation.domain.ValidationFlag;
import com.ssafy.a307.estimatevalidation.domain.ValidationGrade;
import com.ssafy.a307.estimatevalidation.domain.ValidationInput;
import com.ssafy.a307.estimatevalidation.domain.ValidationStatus;
import com.ssafy.a307.estimatevalidation.domain.WorkType;
import com.ssafy.a307.estimatevalidation.domain.WorkTypeMapper;
import com.ssafy.a307.estimatevalidation.dto.ManualValidationItemRequest;
import com.ssafy.a307.estimatevalidation.dto.ManualValidationRequest;
import com.ssafy.a307.estimatevalidation.dto.ValidationAcceptedResponse;
import com.ssafy.a307.estimatevalidation.dto.ValidationHistoryResponse;
import com.ssafy.a307.estimatevalidation.dto.ValidationQuestionResponse;
import com.ssafy.a307.estimatevalidation.dto.ValidationResultResponse;
import com.ssafy.a307.estimatevalidation.dto.ValidationStatusResponse;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidation;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidationItem;
import com.ssafy.a307.estimatevalidation.entity.EstimateValidationQuestion;
import com.ssafy.a307.estimatevalidation.entity.ReferenceSnapshot;
import com.ssafy.a307.estimatevalidation.entity.RepairCostStatReadModel;
import com.ssafy.a307.estimatevalidation.file.SummaryGenerationPort;
import com.ssafy.a307.estimatevalidation.repository.EstimateReadRepository;
import com.ssafy.a307.estimatevalidation.repository.EstimateValidationItemRepository;
import com.ssafy.a307.estimatevalidation.repository.EstimateValidationQuestionRepository;
import com.ssafy.a307.estimatevalidation.repository.EstimateValidationRepository;
import com.ssafy.a307.estimatevalidation.repository.EstimateValidationStateView;
import com.ssafy.a307.estimatevalidation.repository.RepairCostStatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EstimateValidationService {

    public static final String LEGAL_NOTICE =
            "이 결과는 AI와 사례 통계를 이용한 참고용 추정치이며 실제 수리비와 다를 수 있고 특정 사업자를 평가하지 않습니다.";
    private static final int MAX_PAGE_SIZE = 100;

    private final AccidentRepository accidentRepository;
    private final EstimateValidationRepository validationRepository;
    private final EstimateValidationItemRepository itemRepository;
    private final EstimateValidationQuestionRepository questionRepository;
    private final EstimateReadRepository estimateReadRepository;
    private final RepairCostStatRepository repairCostStatRepository;
    private final PartNameMappingService partNameMappingService;
    private final EstimateValidationProperties properties;

    /**
     * 요약 생성 어댑터. <b>없어도 된다</b> — 없으면 규칙 기반 템플릿 요약을 그대로 쓴다.
     * {@code DocumentStoragePort} 와 같은 방식으로 프로퍼티가 켜졌을 때만 빈이 뜬다.
     */
    private final Optional<SummaryGenerationPort> summaryPort;

    private final EstimateValidationEngine engine = new EstimateValidationEngine();
    private final GradeDecider gradeDecider = new GradeDecider();
    private final RepairShopQuestionGenerator questionGenerator = new RepairShopQuestionGenerator();

    @Transactional
    public ValidationAcceptedResponse registerManual(Long memberId, ManualValidationRequest request) {
        Accident accident = ownedAccident(memberId, request.accidentId());
        EstimateReadRepository.EstimateContextView estimate = validateEstimate(request.estimateId(), request.accidentId());

        Map<String, PartNameMappingService.MappedPart> dictionary = partNameMappingService.loadDictionary();
        List<EstimateLine> lines = toLines(request.items(), dictionary);
        int calculatedTotal = calculateClaimedTotal(lines);
        if (request.claimedTotal() != null && request.claimedTotal() != calculatedTotal) {
            throw invalid("claimedTotal이 항목 소계 합계와 일치하지 않습니다.");
        }

        EstimateValidation validation = EstimateValidation.processingManual(
                memberId, accident, request.estimateId(), calculatedTotal);
        runPipeline(validation, accident, estimate, lines, calculatedTotal, Instant.now());
        EstimateValidation saved = validationRepository.save(validation);

        return accepted(saved);
    }

    /**
     * <b>입력 형태에 의존하지 않는 검증 본체.</b> 직접 입력과 파일 입력이 이것을 공유한다.
     *
     * <p>두 경로가 이 로직을 복제하면 <b>같은 견적서가 입력 방식에 따라 다른 등급을 받는</b> 날이
     * 온다. 항목 리스트가 어디서 왔는지(요청 본문이냐 OCR 결과냐)는 여기 들어오기 전에 끝난
     * 문제이고, 여기서부터는 완전히 같아야 한다.
     *
     * <p>호출자가 다른 것은 두 가지뿐이다.
     * <ul>
     *   <li>{@code lines} 의 출처 — 요청 본문 파싱이냐 OCR 결과 변환이냐</li>
     *   <li>{@code validation} 의 상태 — {@code processingManual} 로 갓 만든 것이냐
     *       DB 에 이미 있는 {@code PROCESSING} 행이냐</li>
     * </ul>
     *
     * <p>리포트는 <b>없을 때만</b> 만든다. 파일 경로는 접수 시점에 이미 만들어 두었고
     * ({@code EstimateFileValidationService.registerFile}), 다시 만들면 검증 하나에 리포트가
     * 둘 생긴다.
     *
     * @param claimedTotal 항목 소계 합계. 완료 시 엔티티에 확정된다
     */
    @Transactional
    public void runPipeline(
            EstimateValidation validation,
            Accident accident,
            EstimateReadRepository.EstimateContextView estimate,
            List<EstimateLine> lines,
            int claimedTotal,
            Instant now) {
        List<EstimateReadRepository.AnalyzedPartView> analyzedParts = completedAnalysisParts(estimate);
        AnalysisSnapshot analysis = estimate != null && "COMPLETED".equals(estimate.getAnalysisStatus())
                ? new AnalysisSnapshot(true, analyzedParts.stream()
                        .map(EstimateReadRepository.AnalyzedPartView::getPartCode)
                        .collect(Collectors.toSet()))
                : AnalysisSnapshot.absent();

        Map<Integer, ReferenceSnapshot> snapshots = loadReferences(accident, lines, analyzedParts);
        Map<Integer, CostReference> references = snapshots.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> new CostReference(entry.getValue().costP75(), entry.getValue().caseCount())));

        List<ValidatedLine> validatedLines = engine.validate(new ValidationInput(lines, references, analysis));
        int reviewCount = Math.toIntExact(validatedLines.stream().filter(ValidatedLine::reviewRecommended).count());
        ValidationGrade grade = decideGrade(validatedLines, snapshots, claimedTotal, estimate, reviewCount);
        String summary = summary(grade, reviewCount, validatedLines.size(), claimedTotal, estimate);

        Map<Integer, EstimateValidationItem> persistedItemsByLine = new HashMap<>();
        for (ValidatedLine validated : validatedLines) {
            String reason = reason(validated, snapshots.get(validated.line().lineNo()));
            EstimateValidationItem item = EstimateValidationItem.from(
                    validation, validated, snapshots.get(validated.line().lineNo()), reason);
            validation.addItem(item);
            persistedItemsByLine.put(validated.line().lineNo(), item);
        }

        List<QuestionSource> sources = validatedLines.stream()
                .filter(ValidatedLine::reviewRecommended)
                .map(line -> new QuestionSource(
                        line.line().lineNo(), line.line().rawItemName(), line.line().standardPartName(),
                        line.line().workType(), line.flags()))
                .toList();
        for (GeneratedQuestion generated : questionGenerator.generate(sources)) {
            validation.addQuestion(EstimateValidationQuestion.from(
                    validation, persistedItemsByLine.get(generated.lineNo()), generated, now));
        }
        if (validation.getReport() == null) validation.initializeReport();
        applySummary(validation, grade, summary, reviewCount, validatedLines.size(), claimedTotal, now);
    }

    /**
     * 요약 문장을 정하고 완료로 넘긴다.
     *
     * <p>{@code SummaryGenerationPort} 구현체가 붙어 있으면 그것이 낸 문장을 쓰고, 없거나
     * <b>실패하면 규칙 기반 템플릿으로 조용히 되돌린다.</b> 요약은 이미 계산이 끝난 값을
     * 문장으로 옮기는 일일 뿐이라, 그것 때문에 검증 전체가 실패하면 안 된다.
     *
     * <p>{@code validationId} 가 아직 없는 신규 행(직접 입력)은 요약 어댑터를 부르지 않는다 —
     * {@code SummaryRequest} 가 양수 ID 를 요구한다. 직접 입력 경로의 요약은 지금까지처럼
     * 템플릿이며, 이 점은 answer35 6장에 후속 항목으로 적었다.
     */
    private void applySummary(
            EstimateValidation validation, ValidationGrade grade, String templateSummary,
            int reviewCount, int itemCount, int claimedTotal, Instant now) {
        String text = templateSummary;
        if (summaryPort.isPresent() && validation.getValidationId() != null) {
            try {
                SummaryGenerationPort.GeneratedSummary generated = summaryPort.get().generate(
                        new SummaryGenerationPort.SummaryRequest(
                                validation.getValidationId(), grade.name(), claimedTotal,
                                summaryIssues(validation)));
                text = generated.text();
                validation.recordSummaryModel(generated.model());
            } catch (RuntimeException e) {
                log.warn("요약 생성에 실패해 템플릿 요약을 사용한다. validationId={}",
                        validation.getValidationId(), e);
            }
        }
        validation.complete(grade, text, reviewCount, itemCount, claimedTotal, now);
    }

    /** 요약 모델에 넘길 근거. <b>이미 확정된 항목별 판정만 넘긴다</b> — 새 판단을 맡기지 않는다. */
    private List<SummaryGenerationPort.SummaryIssue> summaryIssues(EstimateValidation validation) {
        return validation.getItems().stream()
                .filter(item -> item.getLlmFlag() != null && item.getLlmReason() != null)
                .map(item -> new SummaryGenerationPort.SummaryIssue(
                        item.getLineNo(), item.getRawItemName(), item.getLlmFlag().name(), item.getLlmReason()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ValidationStatusResponse status(Long memberId, Long validationId) {
        EstimateValidationStateView state = validationRepository
                .findProjectedByValidationIdAndMemberId(validationId, memberId)
                .orElseThrow(this::validationNotFound);
        return new ValidationStatusResponse(
                state.getValidationId(), state.getFileType(), state.getStatus(), state.getFailureReason(),
                state.getCreatedAt(), state.getCompletedAt());
    }

    @Transactional(readOnly = true)
    public ValidationResultResponse result(Long memberId, Long validationId) {
        EstimateValidation validation = validationRepository.findByValidationIdAndMemberId(validationId, memberId)
                .orElseThrow(this::validationNotFound);
        if (validation.getStatus() != ValidationStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.CONFLICT, "완료된 검증 결과가 없습니다.");
        }
        List<EstimateValidationItem> items = itemRepository
                .findByValidation_ValidationIdOrderByLineNoAsc(validationId);
        List<EstimateValidationQuestion> questions = questionRepository
                .findByValidation_ValidationIdOrderByDisplayOrderAsc(validationId);
        EstimateReadRepository.EstimateContextView estimate = validation.getEstimateId() == null
                ? null : estimateReadRepository.findContext(validation.getEstimateId()).orElse(null);
        Accident accident = validation.getAccident();
        Integer actual = accident.getActualRepairCost();

        Integer aiMin = estimate == null ? null : estimate.getTotalMin();
        Integer aiMedian = estimate == null ? null : estimate.getTotalMedian();
        Integer aiMax = estimate == null ? null : estimate.getTotalMax();
        Integer claimed = validation.getClaimedTotal();

        return new ValidationResultResponse(
                validation.getValidationId(), accident.getAccidentId(), validation.getEstimateId(),
                validation.getStatus(), validation.getLlmGrade(), validation.getLlmGrade().displayName(),
                validation.getLlmSummary(), claimed, aiMin, aiMedian, aiMax,
                difference(claimed, aiMedian), difference(claimed, aiMax),
                validation.getReviewItemCount(), validation.getTotalItemCount(),
                items.stream().map(this::resultItem).toList(),
                questions.stream().map(ValidationQuestionResponse::from).toList(),
                actual, accident.getActualRepairCompletedDate(), accident.getRepairShopName(),
                difference(claimed, actual), difference(aiMedian, actual), within(actual, aiMin, aiMax),
                LEGAL_NOTICE, validation.getCreatedAt(), validation.getCompletedAt());
    }

    @Transactional(readOnly = true)
    public List<ValidationQuestionResponse> questions(Long memberId, Long validationId) {
        if (validationRepository.findProjectedByValidationIdAndMemberId(validationId, memberId).isEmpty()) {
            throw validationNotFound();
        }
        return questionRepository.findByValidation_ValidationIdOrderByDisplayOrderAsc(validationId)
                .stream().map(ValidationQuestionResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public ValidationHistoryResponse history(Long memberId, int page, int requestedSize) {
        if (page < 0 || requestedSize < 1) throw invalid("page는 0 이상, size는 1 이상이어야 합니다.");
        int size = Math.min(requestedSize, MAX_PAGE_SIZE);
        Page<EstimateValidation> result = validationRepository
                .findByMemberIdOrderByCreatedAtDesc(memberId, PageRequest.of(page, size));
        List<ValidationHistoryResponse.Entry> entries = result.stream().map(validation -> {
            var accident = validation.getAccident();
            return new ValidationHistoryResponse.Entry(
                    validation.getValidationId(), accident.getAccidentId(),
                    accident.getSnapshotManufacturer(), accident.getSnapshotModelName(),
                    accident.getSnapshotModelYear(), validation.getFileType(),
                    validation.getStatus(), validation.getLlmGrade(), validation.getClaimedTotal(),
                    validation.getReviewItemCount(), validation.getTotalItemCount(),
                    validation.getCreatedAt(), validation.getCompletedAt());
        }).toList();
        return new ValidationHistoryResponse(
                entries, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    public EstimateReadRepository.EstimateContextView validateEstimate(Long estimateId, Long accidentId) {
        if (estimateId == null) return null;
        EstimateReadRepository.EstimateContextView context = estimateReadRepository.findContext(estimateId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "AI 예상 견적을 찾을 수 없습니다."));
        if (!context.getAccidentId().equals(accidentId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "AI 예상 견적을 찾을 수 없습니다.");
        }
        return context;
    }

    public Accident ownedAccident(Long memberId, Long accidentId) {
        return accidentRepository.findByAccidentIdAndMemberId(accidentId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사고 건을 찾을 수 없습니다."));
    }

    /**
     * 항목 요청을 검증용 {@link EstimateLine} 으로 바꾼다. <b>파일 입력 경로가 쓰는 진입점이다.</b>
     *
     * <p>직접 입력이 쓰는 {@code toLines} 를 그대로 부른다 — 작업유형 매핑, 부품명 사전 매핑,
     * 값 범위 검사가 모두 같다. <b>모델이 냈다고 검증을 느슨하게 하지 않는다.</b>
     * 별도 경로를 만들면 두 입력이 같은 항목에 다른 판정을 내리기 시작한다.
     */
    public List<EstimateLine> toEstimateLines(List<ManualValidationItemRequest> items) {
        return toLines(items, partNameMappingService.loadDictionary());
    }

    private List<EstimateLine> toLines(
            List<ManualValidationItemRequest> requests,
            Map<String, PartNameMappingService.MappedPart> dictionary) {
        List<EstimateLine> result = new ArrayList<>(requests.size());
        for (ManualValidationItemRequest item : requests) {
            WorkType workType = WorkTypeMapper.from(item.workType())
                    .orElseThrow(() -> invalid("지원하지 않는 작업유형입니다: " + item.workType()));
            PartNameMappingService.MappedPart mapped = partNameMappingService.map(item.rawItemName(), dictionary);
            try {
                result.add(new EstimateLine(
                        item.lineNo(), item.rawItemName().strip(),
                        mapped == null ? null : mapped.partCode(), mapped == null ? null : mapped.nameKo(),
                        workType, item.quantity(), item.partCost(), item.laborCost()));
            } catch (ArithmeticException | IllegalArgumentException e) {
                throw invalid("견적 항목 값이 허용 범위를 벗어났습니다.");
            }
        }
        return result;
    }

    private int calculateClaimedTotal(List<EstimateLine> lines) {
        try {
            long total = 0;
            for (EstimateLine line : lines) total = Math.addExact(total, line.subtotal());
            return Math.toIntExact(total);
        } catch (ArithmeticException e) {
            throw invalid("견적 합계가 허용 범위를 벗어났습니다.");
        }
    }

    private List<EstimateReadRepository.AnalyzedPartView> completedAnalysisParts(
            EstimateReadRepository.EstimateContextView estimate) {
        if (estimate == null || !"COMPLETED".equals(estimate.getAnalysisStatus())) return List.of();
        return estimateReadRepository.findAnalyzedParts(estimate.getEstimateId());
    }

    private Map<Integer, ReferenceSnapshot> loadReferences(
            Accident accident,
            List<EstimateLine> lines,
            List<EstimateReadRepository.AnalyzedPartView> analyzedParts) {
        Map<String, EstimateReadRepository.AnalyzedPartView> analysisByPart = analyzedParts.stream()
                .collect(Collectors.toMap(
                        EstimateReadRepository.AnalyzedPartView::getPartCode,
                        Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<Integer, ReferenceSnapshot> result = new HashMap<>();
        String carClass = accident.getSnapshotCarClass().getCode();
        for (EstimateLine line : lines) {
            StandardRepairMethod method = line.workType().standardMethod().orElse(null);
            EstimateReadRepository.AnalyzedPartView analyzed = analysisByPart.get(line.partCode());
            if (method == null || analyzed == null) continue;
            repairCostStatRepository
                    .findFirstByCarClassAndPartCodeAndDamageTypeAndRepairMethodOrderByCaseCountDescStatIdAsc(
                            carClass, line.partCode(), analyzed.getDamageType(), method.code())
                    .map(this::snapshot)
                    .ifPresent(snapshot -> result.put(line.lineNo(), snapshot));
        }
        return result;
    }

    private ReferenceSnapshot snapshot(RepairCostStatReadModel stat) {
        return new ReferenceSnapshot(
                stat.getCostMin(), stat.getCostMedian(), stat.getCostP75(), stat.getCostMax(), stat.getCaseCount());
    }

    private ValidationGrade decideGrade(
            List<ValidatedLine> lines,
            Map<Integer, ReferenceSnapshot> snapshots,
            int claimedTotal,
            EstimateReadRepository.EstimateContextView estimate,
            int reviewCount) {
        BigDecimal highest = BigDecimal.ZERO;
        for (ValidatedLine line : lines) {
            ReferenceSnapshot reference = snapshots.get(line.line().lineNo());
            if (reference == null) continue;
            BigDecimal ratio = reference.costP75() == 0
                    ? new BigDecimal("999999")
                    : BigDecimal.valueOf(line.line().subtotal())
                            .divide(BigDecimal.valueOf(reference.costP75()), 6, RoundingMode.HALF_UP);
            if (ratio.compareTo(highest) > 0) highest = ratio;
        }
        BigDecimal totalDifferenceRatio = BigDecimal.ZERO;
        if (estimate != null && estimate.getTotalMedian() != null && estimate.getTotalMedian() > 0) {
            totalDifferenceRatio = BigDecimal.valueOf(Math.abs((long) claimedTotal - estimate.getTotalMedian()))
                    .divide(BigDecimal.valueOf(estimate.getTotalMedian()), 6, RoundingMode.HALF_UP);
        }
        return gradeDecider.decide(new GradeAssessment(reviewCount, highest, totalDifferenceRatio), properties);
    }

    private String summary(
            ValidationGrade grade,
            int reviewCount,
            int itemCount,
            int claimedTotal,
            EstimateReadRepository.EstimateContextView estimate) {
        String comparison = estimate == null || estimate.getTotalMedian() == null
                ? "AI 예상 견적 중앙값과는 비교하지 않았습니다."
                : "정비소 견적은 AI 중앙값보다 %,d원 차이납니다."
                        .formatted((long) claimedTotal - estimate.getTotalMedian());
        return "%s: 총 %d개 항목 중 %d개 항목의 확인을 권장합니다. %s"
                .formatted(grade.displayName(), itemCount, reviewCount, comparison);
    }

    private String reason(ValidatedLine validated, ReferenceSnapshot reference) {
        ValidationFlag flag = validated.flags().stream()
                .min(Comparator.comparingInt(ValidationFlag::questionOrder)).orElse(null);
        if (flag == null) return null;
        return switch (flag) {
            case OVER_P75 -> "소계 %,d원이 유사 사례 %d건의 75백분위 %,d원을 초과합니다."
                    .formatted(validated.line().subtotal(), reference.caseCount(), reference.costP75());
            case NOT_IN_ANALYSIS -> "연결된 AI 분석에서 확인되지 않은 부품의 교환 항목입니다.";
            case DUPLICATE_LABOR -> "같은 부품과 표준 작업 기준의 공임이 앞선 행에도 산정되어 있습니다.";
            case UNMAPPED_ITEM -> "표준 부품 코드로 안전하게 매핑하지 못했습니다.";
            case INSUFFICIENT_REFERENCE -> "차급·부품·손상·수리방식에 맞는 비교 자료가 부족합니다.";
        };
    }

    private ValidationResultResponse.Item resultItem(EstimateValidationItem item) {
        return new ValidationResultResponse.Item(
                item.getLineNo(), item.getRawItemName(), item.getNormalizedItemName(), item.getPartCode(),
                item.getWorkType(), item.getQuantity(), item.getPartCost(), item.getLaborCost(), item.getSubtotal(),
                item.getReferenceMin(), item.getReferenceMedian(), item.getReferenceP75(), item.getReferenceMax(),
                item.getReferenceCaseCount(), item.getLlmFlag(), item.getLlmReason(),
                item.getLlmFlag() == null ? "범위 내" : "확인 권장");
    }

    private ValidationAcceptedResponse accepted(EstimateValidation validation) {
        return new ValidationAcceptedResponse(
                validation.getValidationId(), validation.getStatus(), validation.getFileType(),
                "/api/estimate-validations/" + validation.getValidationId());
    }

    private static Long difference(Integer left, Integer right) {
        return left == null || right == null ? null : (long) left - right;
    }

    private static Boolean within(Integer value, Integer min, Integer max) {
        return value == null || min == null || max == null ? null : value >= min && value <= max;
    }

    private BusinessException validationNotFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "견적서 검증을 찾을 수 없습니다.");
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.INVALID_REQUEST, message);
    }
}
