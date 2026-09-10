package com.ssafy.a307.repaircase.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimate.domain.RefCondition;
import com.ssafy.a307.estimate.domain.RefConditionReader;
import com.ssafy.a307.estimate.domain.RepairMethodDisplay;
import com.ssafy.a307.repaircase.config.RepairCaseImageProperties;
import com.ssafy.a307.repaircase.dto.SimilarCaseItemResponse;
import com.ssafy.a307.repaircase.dto.SimilarCaseResponse;
import com.ssafy.a307.repaircase.image.RepairCaseImageStoragePort;
import com.ssafy.a307.repaircase.repository.SimilarCaseRepository;
import com.ssafy.a307.repaircase.repository.SimilarCaseRepository.SimilarCaseView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 견적 항목이 참고한 유사 사례 조회.
 *
 * <p><b>검색하지 않는다.</b> AI 서버가 사례를 찾아 견적을 내고 그 사례 ID 를 함께 보낸다.
 * 백엔드는 산정 시점에 저장해 둔 ID({@code ref_condition})로 조회만 한다 — 여기서 다시 찾으면
 * AI 가 실제로 참고한 사례와 달라져 화면의 "이 사례들 보기" 가 근거가 아니게 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimilarCaseService {

    private final SimilarCaseRepository similarCaseRepository;
    private final RefConditionReader refConditionReader;
    private final RepairCaseImageProperties imageProperties;
    private final Optional<RepairCaseImageStoragePort> imageStorage;

    @Transactional(readOnly = true)
    public SimilarCaseResponse ofItem(Long estimateId, Long estimateItemId, Long memberId) {
        var item = similarCaseRepository.findOwnedItem(estimateId, estimateItemId, memberId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND, "존재하지 않는 견적 항목입니다."));

        RefCondition basis = refConditionReader.read(item.getRefCondition());
        List<Long> caseIds = basis.referencedCaseIds();

        return new SimilarCaseResponse(
                item.getEstimateItemId(),
                item.getPartNameKo(),
                item.getRepairMethod(),
                RepairMethodDisplay.displayNameOf(item.getRepairMethod()),
                item.getRefCaseCount(),
                basis.fallbackStage() == null ? null : basis.fallbackStage().name(),
                cases(caseIds, item.getPartCode()));
    }

    /**
     * 사례 목록. <b>AI 가 준 순서를 지킨다</b> — 그 순서가 유사도 순일 수 있다.
     * SQL 의 {@code IN} 은 순서를 보장하지 않으므로 여기서 다시 세운다.
     *
     * <p>없는 사례 ID 는 조용히 빠진다. 사례 데이터가 다시 적재되며 사라졌을 수 있는데,
     * 그 한 건 때문에 조회 전체를 실패시키면 나머지 근거까지 못 보게 된다.
     */
    private List<SimilarCaseItemResponse> cases(List<Long> caseIds, String partCode) {
        if (caseIds.isEmpty()) {
            return List.of();
        }
        Map<Long, SimilarCaseView> found = similarCaseRepository.findCases(caseIds, partCode).stream()
                .collect(Collectors.toMap(SimilarCaseView::getCaseId, Function.identity(),
                        (a, b) -> a, LinkedHashMap::new));

        List<SimilarCaseItemResponse> ordered = new ArrayList<>(caseIds.size());
        for (Long caseId : caseIds) {
            SimilarCaseView view = found.get(caseId);
            if (view != null) {
                ordered.add(SimilarCaseItemResponse.of(view, imageUrl(view.getStorageKey())));
            }
        }
        return List.copyOf(ordered);
    }

    /**
     * 이미지 조회 URL. <b>실패해도 목록을 무너뜨리지 않는다</b> — 그 건만 이미지가 빈다.
     * 저장소 어댑터가 없는 로컬·테스트에서도 목록은 그대로 나간다.
     *
     * <p>키는 로그에만 남긴다. 응답으로 나가면 안 되는 값이다.
     */
    private String imageUrl(String storageKey) {
        if (storageKey == null || storageKey.isBlank() || imageStorage.isEmpty()) {
            return null;
        }
        try {
            return imageStorage.get()
                    .createPresignedDownloadUrl(storageKey, imageProperties.downloadUrlValidity())
                    .url()
                    .toString();
        } catch (RuntimeException e) {
            log.warn("사례 이미지 조회 URL 발급 실패 key={}", storageKey, e);
            return null;
        }
    }
}
