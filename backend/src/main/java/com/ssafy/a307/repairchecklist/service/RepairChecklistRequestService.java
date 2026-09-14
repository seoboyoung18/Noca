package com.ssafy.a307.repairchecklist.service;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.llm.LlmChatPort;
import com.ssafy.a307.repairchecklist.dto.RepairChecklistStatusResponse;
import com.ssafy.a307.repairchecklist.entity.RepairChecklist;
import com.ssafy.a307.repairchecklist.repository.RepairChecklistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * 체크리스트 생성 요청 접수 (S15P21A307-460).
 *
 * <h2>접수만 하고 즉시 응답한다</h2>
 *
 * <p>LLM 호출은 초 단위라 동기로 붙들면 안 된다. 여기서는 행을 {@code QUEUED} 로 만들고 끝이며,
 * 실제 생성은 폴링 워커({@link RepairChecklistWorker})가 한다.
 *
 * <p><b>{@code @Async} 나 이벤트를 쓰지 않는다.</b> 재기동하면 진행 중이던 건이 영영 큐에 남는다 —
 * 아무도 다시 집어 주지 않기 때문이다. 이 저장소에 워커 패턴을 두 가지 만들지 않는다
 * ({@code EstimateValidationWorker} · {@code ValidationPdfWorker} · {@code EstimatePdfWorker} 가
 * 같은 이유로 모두 폴링이다).
 *
 * <h2>없는 사고와 남의 사고를 똑같이 404 로 낸다</h2>
 *
 * <p>403 은 "그 사고는 존재한다" 는 사실을 알려 준다. 소유자 조건은 자바 비교가 아니라
 * <b>쿼리 조건</b>에 있다 — {@code AnalysisProgressService} 와 같은 판단이다.
 *
 * <h2>키가 없으면 접수하지 않고 503 이다</h2>
 *
 * <p>{@code GMS_KEY} 가 없으면 {@link LlmChatPort} 빈이 아예 뜨지 않는다
 * ({@code GmsKeyPresentCondition}). 소비처가 {@code Optional} 로 받아 없으면 503 을 주는 것이
 * 이 저장소의 관례다({@code EstimateFileValidationService.storage()} · {@code AccidentImageService}).
 *
 * <p><b>{@code ReportNarrativeGenerator} 를 베끼지 않았다.</b> 거기는 LLM 이 없어도 규칙 기반
 * 문장으로 PDF 가 나오므로 빈 값을 돌려주면 되지만, <b>여기서는 체크리스트가 결과물 자체다.</b>
 * 접수해 두고 워커가 {@code FAILED} 로 적게 하면 사용자는 기다린 끝에 실패를 보고, 사고당 한 행인
 * {@code uk_rcl_accident} 자리에 실패 행이 눌러앉는다. 애초에 성공할 수 없는 요청은 그 자리에서
 * 거절하는 편이 정직하다. 키를 넣으면 그대로 되므로 503 의 뜻("지금은 못 한다")과도 맞는다.
 *
 * <p>그래도 {@link com.ssafy.a307.repairchecklist.domain.RepairChecklistFailure#LLM_UNAVAILABLE}
 * 은 남겨 두었다 — 접수한 뒤 키가 빠지면 워커 쪽에서 그 사유로 끝난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepairChecklistRequestService {

    private final AccidentRepository accidentRepository;
    private final RepairChecklistRepository checklistRepository;

    /** 키가 없으면 빈 값이다. <b>여기서 호출하지 않는다</b> — 있는지만 본다. */
    private final Optional<LlmChatPort> llmChatPort;

    /**
     * 생성을 요청한다. <b>{@code uk_rcl_accident} 때문에 두 번째 요청은 INSERT 가 아니다.</b>
     *
     * <table>
     *   <caption>이미 있을 때의 처리</caption>
     *   <tr><th>기존 상태</th><th>동작</th></tr>
     *   <tr><td>없음</td><td>{@code QUEUED} 로 새로 만든다</td></tr>
     *   <tr><td>{@code QUEUED}·{@code PROCESSING}</td><td>그대로 돌려준다 — 같은 요청을 두 번
     *       보낸 것이고, 이미 하고 있는 일을 다시 큐에 넣을 이유가 없다</td></tr>
     *   <tr><td>{@code FAILED}</td><td>다시 큐에 올린다. 재생성이 아니라 <b>재시도</b>다 —
     *       실패한 생성은 결과물이 없으므로 "만들어 달라" 는 요청이 아직 유효하다.
     *       이 경로가 없으면 한 번 실패한 사고는 영영 체크리스트를 가질 수 없다
     *       ({@code -486} 재생성은 완성된 것을 새로 만드는 별개 스토리다)</td></tr>
     *   <tr><td>{@code COMPLETED}</td><td><b>409</b>. 이미 있는 것을 갈아엎는 것은 재생성이고
     *       {@code S15P21A307-486} 의 몫이다 — 여기서 조용히 덮으면 사용자가 체크해 둔 항목이
     *       사라진다</td></tr>
     * </table>
     */
    @Transactional
    public RepairChecklistStatusResponse request(Long memberId, Long accidentId) {
        Accident accident = accidentRepository.findByAccidentIdAndMemberId(accidentId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다."));

        if (llmChatPort.isEmpty()) {
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                    "체크리스트 생성을 사용할 수 없습니다.");
        }

        Instant now = Instant.now();
        RepairChecklist checklist = checklistRepository
                .findByAccidentIdAndMemberId(accidentId, memberId)
                .map(existing -> reuse(existing, accidentId))
                .orElseGet(() -> checklistRepository.save(RepairChecklist.queued(accident, now)));

        return RepairChecklistStatusResponse.from(checklist);
    }

    private RepairChecklist reuse(RepairChecklist existing, Long accidentId) {
        switch (existing.getStatus()) {
            case COMPLETED -> throw new BusinessException(ErrorCode.CONFLICT,
                    "이미 생성된 체크리스트가 있습니다.");
            case FAILED -> {
                log.info("실패한 체크리스트를 다시 큐에 올린다: accidentId={}, checklistId={}, 직전사유={}",
                        accidentId, existing.getChecklistId(), existing.getFailureReason());
                existing.requeue();
            }
            default -> log.debug("이미 접수된 체크리스트 요청이다: accidentId={}, status={}",
                    accidentId, existing.getStatus());
        }
        return existing;
    }
}
