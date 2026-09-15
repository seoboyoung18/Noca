package com.ssafy.a307.repairchecklist.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.llm.LlmChatPort;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistStatusResponse;
import com.ssafy.a307.repairchecklist.entity.RepairChecklist;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistStatus;
import com.ssafy.a307.repairchecklist.repository.RepairChecklistItemRepository;
import com.ssafy.a307.repairchecklist.repository.RepairChecklistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 완성된 체크리스트 재생성 (S15P21A307-486).
 *
 * <h2>사용자 항목을 남긴다 — 이 작업의 핵심 판단</h2>
 *
 * <p>{@code AI}·{@code COMMON} 은 지우고 {@code USER} 는 <b>체크 상태와 메모까지 그대로</b>
 * 남긴다. 사용자가 직접 적은 것을 서버가 지우면 안 된다 — 다시 만들어 달라는 요청은 "AI 가 뽑아
 * 준 것을 다시 뽑아 달라" 는 뜻이지 "내가 적은 것도 버려 달라" 는 뜻이 아니다.
 *
 * <p>{@code S15P21A307-509} 가 {@code deleteAllByChecklistId} 주석에 "재생성이 붙을 때는 사용자
 * 항목을 어떻게 할지 그쪽에서 정해야 한다" 고 미뤄 둔 결정이고, 여기가 그 "그쪽" 이다.
 *
 * <h2>머리를 새로 만들지 않는다</h2>
 *
 * <p>{@code uk_rcl_accident} 가 사고당 한 행을 강제한다. {@link RepairChecklist#regenerate} 가
 * {@code generationNo} 와 {@code regeneratedAt} 을 <b>함께</b> 올리고 상태를 {@code QUEUED} 로
 * 되돌린다 — 따로 두면 {@code ck_rcl_regen} 을 어기는 호출이 가능해진다.
 *
 * <h2>생성은 기존 워커가 한다</h2>
 *
 * <p>여기서 LLM 을 부르지 않는다. {@code QUEUED} 로 돌려놓으면 {@code RepairChecklistWorker} 가
 * 다음 주기에 집어 새 {@code AI}·{@code COMMON} 항목을 채운다. <b>워커는 고치지 않았다.</b>
 * 다만 워커가 부르는 {@code RepairChecklistProcessor} 의 "앞 시도 정리" 가 전부 지우는 것이라면
 * 여기서 남긴 사용자 항목이 몇 초 뒤에 사라지므로, 그 한 줄은
 * {@code deleteGeneratedByChecklistId} 로 맞췄다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepairChecklistRegenerateService {

    private final RepairChecklistRepository checklistRepository;
    private final RepairChecklistItemRepository itemRepository;

    /** 키가 없으면 빈 값이다. <b>여기서 호출하지 않는다</b> — 있는지만 본다. */
    private final Optional<LlmChatPort> llmChatPort;

    /**
     * 다시 만든다. 접수만 하고 즉시 돌아온다 — 결과는 상태 조회로 본다.
     *
     * <table>
     *   <caption>상태별 처리</caption>
     *   <tr><th>기존 상태</th><th>동작</th></tr>
     *   <tr><td>없음</td><td><b>404</b>. 만든 적 없는 것을 "다시" 만들 수는 없다 — 첫 생성은
     *       {@code POST /repair-checklist} 다</td></tr>
     *   <tr><td>{@code QUEUED}·{@code PROCESSING}</td><td><b>409</b>. 이미 만들고 있다. 여기서
     *       세대를 또 올리면 워커가 어느 세대를 만들고 있는지 알 수 없어진다</td></tr>
     *   <tr><td>{@code FAILED}</td><td><b>409</b>. 실패한 것은 <b>재시도</b>지 재생성이 아니다 —
     *       {@code POST /repair-checklist} 가 큐로 돌려보낸다. 여기서 받아 주면 결과물이 없는데도
     *       {@code generationNo} 만 올라간다</td></tr>
     *   <tr><td>{@code COMPLETED}</td><td>재생성한다</td></tr>
     * </table>
     */
    @Transactional
    public RepairChecklistStatusResponse regenerate(Long memberId, Long accidentId) {
        RepairChecklist checklist = checklistRepository
                .findByAccidentIdAndMemberId(accidentId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "체크리스트를 찾을 수 없습니다."));

        if (checklist.getStatus() != RepairChecklistStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.CONFLICT,
                    "완성된 체크리스트만 다시 만들 수 있습니다.");
        }
        if (llmChatPort.isEmpty()) {
            // 접수해 두고 워커가 FAILED 로 적게 하면, 멀쩡히 완성돼 있던 체크리스트가
            // 실패 상태로 바뀌어 남는다. 성공할 수 없는 요청은 그 자리에서 거절한다
            // (RepairChecklistRequestService 와 같은 판단).
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "체크리스트 생성을 사용할 수 없습니다.");
        }

        int removed = itemRepository.deleteGeneratedByChecklistId(checklist.getChecklistId());
        checklist.regenerate(Instant.now());

        log.info("체크리스트 재생성 접수: checklistId={}, 생성분 {}건 삭제, 세대={}",
                checklist.getChecklistId(), removed, checklist.getGenerationNo());

        // 항목을 싣지 않는다. 지금은 사용자 항목만 남아 있어, 그대로 보여 주면 "다시 만든 결과"
        // 처럼 읽힌다. 완성되면 조회가 전부 준다.
        return RepairChecklistStatusResponse.from(checklist);
    }
}
