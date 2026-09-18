package com.ssafy.a307.estimate.narrative;

import com.ssafy.a307.estimate.dto.EstimateBasisItemResponse;
import com.ssafy.a307.estimate.dto.EstimateBasisResponse;
import com.ssafy.a307.estimate.dto.EstimateItemResponse;
import com.ssafy.a307.estimate.dto.EstimateResponse;
import com.ssafy.a307.estimate.repository.EstimateReportRepository;
import com.ssafy.a307.estimate.service.EstimateQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 요약 한 건을 실제로 만든다 (S15P21A307-537). <b>트랜잭션 경계를 위해 워커에서 떼어낸 빈이다</b>
 * — 워커 안에 두고 {@code this.claim(...)} 으로 부르면 프록시를 거치지 않아
 * {@code REQUIRES_NEW} 가 통째로 무시된다({@code RepairChecklistProcessor} 와 같은 이유).
 *
 * <h2>사용자와 같은 조립 경로를 탄다</h2>
 *
 * <p>요청자가 없는 내부 경로라 견적 주인을 읽어 {@link EstimateQueryService} 를 그대로 쓴다
 * ({@code EstimatePdfProcessor} 가 같은 방식이다). 요약이 보는 값과 화면이 보는 값이 어긋나면
 * "요약은 세 군데라는데 표에는 두 개" 같은 일이 생긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EstimateNarrativeProcessor {

    private final EstimateNarrativeRepository narrativeRepository;
    private final EstimateReportRepository reportRepository;
    private final EstimateQueryService estimateQueryService;
    private final EstimateNarrativeGenerator generator;
    private final ObjectMapper objectMapper;

    /**
     * {@code QUEUED} 인 건만 {@code PROCESSING} 으로 선점한다.
     *
     * @return 내가 선점했으면 true. 0행이면 다른 워커가 이미 가져갔다는 뜻이라 건너뛴다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long estimateId) {
        return narrativeRepository.claimQueued(estimateId, Instant.now()) == 1;
    }

    /**
     * 선점한 건을 만든다.
     *
     * @throws EstimateNarrativeGenerationException 이 건만 실패. 워커가 받아 {@link #markFailed} 로 적는다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long estimateId) {
        EstimateNarrative narrative = narrativeRepository.findById(estimateId)
                .orElseThrow(() -> new EstimateNarrativeGenerationException(
                        EstimateNarrativeFailure.INTERNAL, "선점한 요약이 사라졌다: " + estimateId));

        Long ownerId = narrativeRepository.findOwnerMemberId(estimateId)
                .orElseThrow(() -> new EstimateNarrativeGenerationException(
                        EstimateNarrativeFailure.INTERNAL, "견적이 사라졌다: " + estimateId));

        EstimateNarrativeContent content = generator.generate(context(estimateId, ownerId));
        narrative.markCompleted(objectMapper.writeValueAsString(content), Instant.now());
        log.info("견적 요약 완료: estimateId={}, 확인권장={}건, 근거문장={}건",
                estimateId, content.cautions().size(), content.basisNotes().size());
    }

    /**
     * 실패를 기록한다. <b>{@link #process} 와 다른 트랜잭션이어야 한다</b> — 같은 트랜잭션이면
     * 처리 실패와 함께 롤백돼 사유가 남지 않는다.
     *
     * <p>이미 끝난 건은 건드리지 않는다. 워커 둘이 겹쳐 돌 때 한쪽이 완료한 건을 다른 쪽이
     * 실패로 덮는 일을 막는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long estimateId, EstimateNarrativeFailure failure) {
        narrativeRepository.findById(estimateId).ifPresent(narrative -> {
            if (narrative.completed()) {
                return;
            }
            narrative.markFailed(failure, Instant.now());
        });
    }

    /**
     * 지시문에 실을 값을 모은다. <b>새로 계산하지 않는다</b> — 화면이 쓰는 응답을 그대로 읽는다.
     *
     * <p>근거 문장은 {@code BasisNarrative} 가 규칙으로 만든 것이고, LLM 은 그것을 다듬기만 한다.
     * 근거가 없는 항목({@code basisAvailable=false})은 문장 자리를 비워 보낸다 — 다듬을 원문이
     * 없으면 그 자리에 문장이 생겨서는 안 된다.
     */
    private EstimateNarrativeContext context(Long estimateId, Long ownerId) {
        EstimateResponse estimate = estimateQueryService.detail(estimateId, ownerId);
        EstimateBasisResponse basis = estimateQueryService.basis(estimateId, ownerId);

        Map<String, EstimateBasisItemResponse> basisByPart = new LinkedHashMap<>();
        for (EstimateBasisItemResponse item : basis.items()) {
            if (item.partCode() != null) {
                basisByPart.putIfAbsent(item.partCode(), item);
            }
        }

        List<EstimateNarrativeContext.ItemView> items = new ArrayList<>();
        for (EstimateItemResponse item : estimate.items()) {
            EstimateBasisItemResponse itemBasis = basisByPart.get(item.partCode());
            items.add(new EstimateNarrativeContext.ItemView(
                    item.partCode(), item.partNameKo(), item.repairMethodDisplayName(),
                    item.itemMedian(), item.refCaseCount(),
                    itemBasis == null ? null : itemBasis.narrative()));
        }

        List<EstimateNarrativeContext.UnresolvedView> unresolved = estimate.unresolvedParts().stream()
                .map(part -> new EstimateNarrativeContext.UnresolvedView(
                        part.partCode(), part.partNameKo(), part.reasonDisplayName()))
                .toList();

        var vehicle = reportRepository.findContext(estimateId, ownerId)
                .orElseThrow(() -> new EstimateNarrativeGenerationException(
                        EstimateNarrativeFailure.INTERNAL, "견적 맥락을 읽지 못했다: " + estimateId));

        return new EstimateNarrativeContext(
                vehicle.getManufacturer(), vehicle.getModelName(), vehicle.getModelYear(),
                estimate.estimable(), estimate.nonEstimableReason(),
                estimate.totalMin(), estimate.totalMedian(), estimate.totalMax(),
                estimate.refCaseTotal(), items, unresolved);
    }
}
