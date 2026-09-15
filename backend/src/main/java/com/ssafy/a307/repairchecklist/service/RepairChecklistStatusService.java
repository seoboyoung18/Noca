package com.ssafy.a307.repairchecklist.service;

import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.estimate.service.EstimateNoticeProvider;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistItemResponse;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistStatusResponse;
import com.ssafy.a307.repairchecklist.entity.RepairChecklist;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistStatus;
import com.ssafy.a307.repairchecklist.repository.RepairChecklistItemRepository;
import com.ssafy.a307.repairchecklist.repository.RepairChecklistRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 체크리스트 생성 상태 · 항목 · 진행률 조회 (S15P21A307-461 · -483).
 * <b>조회만 한다</b> — 상태를 옮기는 코드가 없다.
 *
 * <p>없는 사고와 남의 사고는 <b>모두 404</b>, 사고는 있는데 아직 요청하지 않았으면
 * <b>빈 상태 200</b> 이다. {@code AnalysisProgressService} 와 같은 형태다 — 빈 상태를 404 로 내면
 * 화면이 "없는 사고" 와 "생성 전" 을 구분하지 못한다.
 *
 * <p><b>항목을 한 응답에 함께 담는다.</b> 이유는 {@link RepairChecklistStatusResponse} 에 적어
 * 두었다. 진행률은 그 항목을 세서 낸다 — {@code repair_checklist} 에 진행률 열이 없는 것은
 * {@code S15P21A307-509} 의 결정이고, 조회가 항목을 이미 읽으므로 세는 데 추가 질의가 들지 않는다.
 *
 * <p><b>생성이 끝난 것만 항목을 읽는다.</b> {@code PROCESSING} 중에 읽으면 워커가 쓰는 중인
 * 부분 결과가 보이고, 재시도로 큐에 돌아간 건은 앞 시도의 항목이 최신처럼 보인다.
 */
@Service
@RequiredArgsConstructor
public class RepairChecklistStatusService {

    private final AccidentRepository accidentRepository;
    private final RepairChecklistRepository checklistRepository;
    private final RepairChecklistItemRepository itemRepository;
    private final EstimateNoticeProvider noticeProvider;

    @Transactional(readOnly = true)
    public RepairChecklistStatusResponse status(Long memberId, Long accidentId) {
        if (!accidentRepository.existsByAccidentIdAndMemberId(accidentId, memberId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다.");
        }

        // 문구가 없어도 조회는 200 이다. 고지 하나 때문에 체크리스트 화면 전체를 막지 않는다.
        String notice = noticeProvider.guidanceLimitNotice();

        return checklistRepository.findByAccidentIdAndMemberId(accidentId, memberId)
                .map(checklist -> RepairChecklistStatusResponse.from(checklist, items(checklist), notice))
                .orElseGet(() -> RepairChecklistStatusResponse.notRequested(notice));
    }

    /** {@code ix_rcli_checklist (checklist_id, display_order)} 가 이 정렬을 그대로 커버한다. */
    private List<RepairChecklistItemResponse> items(RepairChecklist checklist) {
        if (checklist.getStatus() != RepairChecklistStatus.COMPLETED) {
            return List.of();
        }
        return itemRepository
                .findByChecklist_ChecklistIdOrderByDisplayOrderAscItemIdAsc(checklist.getChecklistId())
                .stream()
                .map(RepairChecklistItemResponse::from)
                .toList();
    }
}
