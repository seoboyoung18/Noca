package com.ssafy.a307.estimate.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimate.domain.RefConditionReader;
import com.ssafy.a307.estimate.dto.EstimateBasisItemResponse;
import com.ssafy.a307.estimate.dto.EstimateBasisResponse;
import com.ssafy.a307.estimate.dto.EstimateItemResponse;
import com.ssafy.a307.estimate.dto.EstimateNotice;
import com.ssafy.a307.estimate.dto.EstimateResponse;
import com.ssafy.a307.estimate.dto.EstimateSummaryResponse;
import com.ssafy.a307.estimate.repository.EstimateQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

    @Transactional(readOnly = true)
    public EstimateResponse detail(Long estimateId, Long memberId) {
        var view = estimateQueryRepository.findDetail(estimateId, memberId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "존재하지 않는 견적입니다."));

        List<EstimateItemResponse> items = estimateQueryRepository.findItems(estimateId).stream()
                .map(EstimateItemResponse::from)
                .toList();

        return EstimateResponse.of(view, items, notices());
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

        List<EstimateBasisItemResponse> items =
                estimateQueryRepository.findBasisItems(estimateId).stream()
                        .map(item -> EstimateBasisItemResponse.of(
                                item, refConditionReader.read(item.getRefCondition())))
                        .toList();

        return new EstimateBasisResponse(view.getEstimateId(), view.getVersion(), items);
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
     * 고지 문구. <b>지금은 항상 비어 있다.</b>
     * <p>
     * 문구를 담을 테이블이 아직 없다(S15P21A307-288). 여기서 문자열을 상수로 박으면
     * "코드 수정 없이 변경 가능하도록" 이라는 그 티켓의 요구가 그 자리에서 깨진다.
     * 필드를 미리 내려 두는 것은 288 이 들어와도 <b>계약이 바뀌지 않게</b> 하려는 것이다.
     */
    private List<EstimateNotice> notices() {
        return List.of();
    }
}
