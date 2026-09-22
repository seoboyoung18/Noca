package com.ssafy.a307.estimate.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimate.domain.RefConditionReader;
import com.ssafy.a307.estimate.domain.UnresolvedPart;
import com.ssafy.a307.estimate.domain.UnresolvedPartsReader;
import com.ssafy.a307.estimate.domain.UnresolvedReasonDisplay;
import com.ssafy.a307.estimate.dto.EstimateBasisItemResponse;
import com.ssafy.a307.estimate.dto.EstimateBasisResponse;
import com.ssafy.a307.estimate.dto.EstimateItemResponse;
import com.ssafy.a307.estimate.dto.EstimateNotice;
import com.ssafy.a307.estimate.dto.EstimateResponse;
import com.ssafy.a307.estimate.dto.EstimateSummaryResponse;
import com.ssafy.a307.estimate.dto.UnresolvedPartResponse;
import com.ssafy.a307.estimate.narrative.EstimateNarrative;
import com.ssafy.a307.estimate.narrative.EstimateNarrativeContent;
import com.ssafy.a307.estimate.narrative.EstimateNarrativeReader;
import com.ssafy.a307.estimate.narrative.EstimateNarrativeRepository;
import com.ssafy.a307.estimate.repository.EstimateQueryRepository;
import com.ssafy.a307.estimatevalidation.entity.PartCode;
import com.ssafy.a307.estimatevalidation.repository.PartCodeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 견적 조회. 상세 한 건과 사고별 이력 목록을 준다.
 *
 * <p>소유자 검사는 쿼리 조건이 한다. 여기서는 결과가 비었을 때 404 로 바꾸는 일만 한다 —
 * <b>남의 견적과 없는 견적을 같은 응답으로 돌려주기 위해서다.</b> 403 과 404 를 나누면
 * "그 견적은 존재한다"는 사실이 새어 나간다.
 */
@Service
@RequiredArgsConstructor
public class EstimateQueryService {

    private final EstimateQueryRepository estimateQueryRepository;
    private final RefConditionReader refConditionReader;
    private final UnresolvedPartsReader unresolvedPartsReader;
    private final PartCodeRepository partCodeRepository;
    private final EstimateNoticeProvider noticeProvider;

    /** 다듬어진 근거 문장 (S15P21A307-537). 없으면 규칙이 만든 문장이 그대로 나간다. */
    private final EstimateNarrativeRepository narrativeRepository;
    private final EstimateNarrativeReader narrativeReader;

    @Transactional(readOnly = true)
    public EstimateResponse detail(Long estimateId, Long memberId) {
        var view = estimateQueryRepository.findDetail(estimateId, memberId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "존재하지 않는 견적입니다."));

        // 합친 손상 유형은 근거 스냅샷에 있다 (S15P21A307-566). 항목 표가 "긁힘·깨짐" 을 보여 주려면
        // 근거 응답까지 가지 않고 항목에서 바로 읽을 수 있어야 한다.
        List<EstimateItemResponse> items = estimateQueryRepository.findItems(estimateId).stream()
                .map(item -> EstimateItemResponse.from(item,
                        refConditionReader.read(item.getRefCondition()).mergedDamageTypes()))
                .toList();

        return EstimateResponse.of(view, items, unresolvedParts(view.getUnresolvedParts()), notices());
    }

    /**
     * 산정하지 못한 부위 (S15P21A307-534).
     *
     * <p><b>이름을 조회할 때 붙인다.</b> 저장할 때 복사하면 마스터 이름을 고쳤을 때 과거 견적만
     * 옛 이름으로 남는다 — {@code findItems} 가 {@code part_code} 를 조인하는 것과 같은 판단이다.
     *
     * <p>정렬은 {@code items} 와 같은 부위 표시 순서다. 마스터에서 행이 사라진 부위는 이름 없이
     * 맨 뒤에 둔다 — 빼 버리면 "총액에서 뺀 부위" 안내가 조용히 줄어든다.
     */
    private List<UnresolvedPartResponse> unresolvedParts(String json) {
        List<UnresolvedPart> parts = unresolvedPartsReader.read(json);
        if (parts.isEmpty()) {
            return List.of();
        }
        Map<String, PartCode> master = partCodeRepository
                .findAllById(parts.stream().map(UnresolvedPart::partCode).toList()).stream()
                .collect(Collectors.toMap(PartCode::getPartCode, Function.identity()));

        return parts.stream()
                .sorted(Comparator
                        .comparingInt((UnresolvedPart part) -> displayOrder(master.get(part.partCode())))
                        .thenComparing(UnresolvedPart::partCode))
                .map(part -> new UnresolvedPartResponse(
                        part.partCode(),
                        master.containsKey(part.partCode()) ? master.get(part.partCode()).getNameKo() : null,
                        part.damageType(),
                        part.reason(),
                        UnresolvedReasonDisplay.displayNameOf(part.reason())))
                .toList();
    }

    private static int displayOrder(PartCode partCode) {
        return partCode == null ? Integer.MAX_VALUE : partCode.getDisplayOrder();
    }

    /**
     * 항목별 산정 근거. 견적 조회와 나눈 것은 근거가 "자세히 보기"로 펼쳐 보는 것이고
     * {@code ref_condition} 이 JSONB 라 응답이 커지기 때문이다.
     *
     * <p>소유자 검사는 {@link #detail} 과 같은 경로를 탄다 — 근거에도 수리비 통계가 들어 있어
     * 남에게 보이면 안 되고, 두 API 가 서로 다른 판정을 내리면 한쪽으로 새어 나간다.
     */
    @Transactional(readOnly = true)
    public EstimateBasisResponse basis(Long estimateId, Long memberId) {
        var view = estimateQueryRepository.findDetail(estimateId, memberId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "존재하지 않는 견적입니다."));

        // 다듬어진 문장이 있으면 그것으로 바꾼다. 없으면(생성 전·실패·이 기능 이전 견적)
        // 규칙이 만든 문장이 그대로 나간다 — 근거 섹션은 요약보다 먼저 있던 것이다.
        EstimateNarrativeContent narrative = narrativeOf(estimateId);
        List<EstimateBasisItemResponse> items =
                estimateQueryRepository.findBasisItems(estimateId).stream()
                        .map(item -> EstimateBasisItemResponse.of(
                                item, refConditionReader.read(item.getRefCondition())))
                        .map(item -> item.withNarrative(narrative.noteFor(item.partCode())))
                        .toList();

        return new EstimateBasisResponse(view.getEstimateId(), view.getVersion(), items);
    }

    /**
     * 저장된 요약. <b>완료된 것만 읽는다</b> — 생성 중이거나 실패한 건의 문장을 보여 주면
     * 만들다 만 값이 최신처럼 보인다.
     */
    private EstimateNarrativeContent narrativeOf(Long estimateId) {
        return narrativeRepository.findById(estimateId)
                .filter(EstimateNarrative::completed)
                .map(EstimateNarrative::getContent)
                .map(narrativeReader::read)
                .orElseGet(EstimateNarrativeContent::empty);
    }

    /**
     * 사고에 딸린 견적 이력.
     *
     * @param latestOnly 기본값이다. 화면은 대개 최신 한 건만 쓰고, 이력 전체는
     *                   "이전 견적 보기"를 눌렀을 때만 필요하다
     */
    @Transactional(readOnly = true)
    public List<EstimateSummaryResponse> history(Long accidentId, Long memberId, boolean latestOnly) {
        // 견적이 아직 없는 것과 남의 사고인 것은 다르다. 전자는 빈 목록, 후자는 404 다
        if (!estimateQueryRepository.existsOwnedAccident(accidentId, memberId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "존재하지 않는 사고입니다.");
        }

        List<EstimateSummaryResponse> history =
                estimateQueryRepository.findHistoryByAccident(accidentId, memberId).stream()
                        .map(EstimateSummaryResponse::from)
                        .toList();

        if (latestOnly && !history.isEmpty()) {
            return List.of(history.getFirst());
        }
        return history;
    }

    /**
     * 고지 문구 (S15P21A307-288).
     * <p>
     * {@code estimate_notice} 테이블에서 읽는다. 운영자가 {@code psql UPDATE} 로 문장을 바꾸면
     * 재배포 없이 다음 요청부터 반영된다 — 그것이 이 티켓의 요구였다.
     * <p>
     * 문구가 한 건도 없으면 빈 목록이다. 견적 조회는 그래도 200 으로 나간다 — 고지가 필수인
     * 것은 사용자가 밖으로 들고 가는 리포트 쪽이고, 그 검사는
     * {@code EstimateReportService.requireSections()} 가 한다.
     */
    private List<EstimateNotice> notices() {
        return noticeProvider.activeNotices();
    }
}
