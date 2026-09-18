package com.ssafy.a307.repairchecklist.service;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.analysis.entity.AnalysisJob;
import com.ssafy.a307.analysis.entity.AnalysisJobStatus;
import com.ssafy.a307.analysis.repository.AnalysisJobRepository;
import com.ssafy.a307.analysis.repository.DamagedPartRepository;
import com.ssafy.a307.repairchecklist.domain.RepairChecklistDraft;
import com.ssafy.a307.repairchecklist.domain.RepairChecklistFailure;
import com.ssafy.a307.repairchecklist.domain.RepairChecklistGenerationException;
import com.ssafy.a307.repairchecklist.entity.RepairChecklist;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistCommonItem;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistItem;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistStatus;
import com.ssafy.a307.repairchecklist.repository.RepairChecklistCommonItemRepository;
import com.ssafy.a307.repairchecklist.repository.RepairChecklistItemRepository;
import com.ssafy.a307.repairchecklist.repository.RepairChecklistRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 체크리스트 한 건을 실제로 만든다. <b>트랜잭션 경계를 위해 워커에서 떼어낸 빈이다.</b>
 *
 * <p>워커 안에 두고 {@code this.claim(...)} 으로 부르면 프록시를 거치지 않아
 * {@code @Transactional} 이 먹지 않고 {@code @Modifying} 쿼리가 터진다
 * ({@code EstimateFileProcessor} 가 같은 이유로 나뉘어 있다). 선점은 처리와도 분리돼야 한다 —
 * 처리 트랜잭션이 롤백돼도 선점은 남아야 같은 건을 무한히 다시 집지 않는다.
 *
 * <h2>항목 순서 (S15P21A307-463)</h2>
 *
 * <p><b>AI 항목이 먼저(1..N), 공통 항목이 뒤(N+1..N+6)</b>다. 공통 항목은 어느 사고에나 똑같이
 * 붙는 절차라 먼저 놓으면 <b>모든 사고의 체크리스트가 같은 여섯 줄로 시작한다.</b> 사용자가 이
 * 화면에서 찾는 것은 "내 사고에서 무엇을 물어야 하나" 이므로 사고별 항목이 위로 간다. 공통 항목
 * 사이의 순서는 마스터 {@code display_order} 를 그대로 따른다 — 그 순서는 기획이 정한 것이고
 * 서버가 다시 정렬할 이유가 없다.
 *
 * <p>{@code display_order} 는 <b>이어지는 정수</b>다. 공통 항목에 1000번대를 예약하는 식으로
 * 띄우지 않았다 — 사용자 추가 항목({@code S15P21A307-485})이 어디에 끼는지는 그 스토리가 정할
 * 일이고, 지금 빈 번호를 만들어 두면 그 결정을 미리 못 박는 셈이 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepairChecklistProcessor {

    private final RepairChecklistRepository checklistRepository;
    private final RepairChecklistItemRepository itemRepository;
    private final RepairChecklistCommonItemRepository commonItemRepository;
    private final AnalysisJobRepository analysisJobRepository;
    private final DamagedPartRepository damagedPartRepository;
    private final RepairChecklistGenerator generator;

    /**
     * {@code QUEUED} 인 건만 {@code PROCESSING} 으로 선점한다.
     *
     * @return 내가 선점했으면 true. 0행이면 다른 워커가 이미 가져갔다는 뜻이라 건너뛴다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(Long checklistId) {
        return checklistRepository.claimQueued(checklistId) == 1;
    }

    /**
     * 선점한 건을 만든다.
     *
     * @throws RepairChecklistGenerationException 이 건만 실패. 워커가 받아 {@link #markFailed} 로 기록한다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void process(Long checklistId) {
        RepairChecklist checklist = checklistRepository.findById(checklistId)
                .orElseThrow(() -> new RepairChecklistGenerationException(
                        RepairChecklistFailure.INTERNAL, "선점한 체크리스트가 사라졌다: " + checklistId));

        RepairChecklistDraft draft = generator.generate(context(checklist.getAccident()));

        // 앞 시도가 남긴 생성분을 먼저 비운다. 실패한 건을 다시 만들 때 두 시도가 섞이면
        // 사용자는 어느 줄이 최신인지 알 수 없다. 벌크 DELETE 라 이 줄에서 바로 나간다 —
        // 파생 삭제였다면 새 공통 항목 INSERT 가 먼저 나가 uk_rcli_common 을 위반한다.
        //
        // ★ USER 항목은 남긴다 (S15P21A307-485 · -486). 재생성이 사용자 항목을 남겨 두고
        //   QUEUED 로 되돌리는데, 여기서 전부 지우면 그 항목이 몇 초 뒤에 사라진다.
        int stale = itemRepository.deleteGeneratedByChecklistId(checklistId);
        if (stale > 0) {
            log.info("앞 시도가 남긴 항목을 지웠다: checklistId={}, {}건", checklistId, stale);
        }

        Instant now = Instant.now();
        List<RepairChecklistItem> items = new ArrayList<>();
        int order = 1;
        for (RepairChecklistDraft.DraftItem item : draft.items()) {
            items.add(RepairChecklistItem.ai(checklist, item.content(), item.category(),
                    item.partCode(), item.reason(), order++, now));
        }

        List<RepairChecklistCommonItem> masters =
                commonItemRepository.findByActiveTrueOrderByDisplayOrderAscCodeAsc();
        for (RepairChecklistCommonItem master : masters) {
            items.add(RepairChecklistItem.common(checklist, master, order++, now));
        }

        itemRepository.saveAll(items);
        checklist.markCompleted(draft.summary(), now);
        log.info("체크리스트 생성 완료: checklistId={}, AI={}건, 공통={}건, 요약={}",
                checklistId, draft.items().size(), masters.size(),
                draft.summary() == null ? "없음" : "있음");
    }

    /**
     * 실패를 기록한다. <b>{@link #process} 와 다른 트랜잭션이어야 한다</b> — 같은 트랜잭션이면
     * 처리 실패와 함께 롤백돼 사유가 남지 않는다.
     *
     * <p>이미 끝난 건은 건드리지 않는다. 워커 둘이 겹쳐 돌 때 한쪽이 완료한 건을 다른 쪽이
     * 실패로 덮는 일을 막는다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long checklistId, RepairChecklistFailure failure) {
        checklistRepository.findById(checklistId).ifPresent(checklist -> {
            if (checklist.getStatus() == RepairChecklistStatus.COMPLETED) {
                return;
            }
            checklist.markFailed(failure.name(), Instant.now());
        });
    }

    /**
     * 지시문에 실을 사고 정보를 모은다.
     *
     * <p>손상 부위는 <b>가장 최근 {@code COMPLETED} 분석</b>에서 읽는다. 진행 중이거나 실패한
     * 작업의 부분 결과로 항목을 만들면, 분석이 끝난 뒤 내용이 달라져도 체크리스트는 그대로 남는다.
     *
     * <p><b>분석이 없어도 만든다.</b> 사용자가 분석을 돌리기 전에 정비소부터 가는 흐름을 막을
     * 이유가 없고, 그때는 차량 정보만으로 일반적인 항목이 나온다. 없는 손상을 지어내지 말라는
     * 지시는 지시문에 들어 있다.
     */
    private RepairChecklistContext context(Accident accident) {
        List<RepairChecklistContext.DamagedPartView> parts = analysisJobRepository
                .findByAccident_AccidentIdOrderByCreatedAtDescJobIdDesc(accident.getAccidentId()).stream()
                .filter(job -> job.getStatus() == AnalysisJobStatus.COMPLETED)
                .findFirst()
                .map(AnalysisJob::getJobId)
                .map(damagedPartRepository::findByJob_JobIdOrderByPartCodeAsc)
                .orElseGet(List::of)
                .stream()
                .map(part -> new RepairChecklistContext.DamagedPartView(
                        part.getPartCode(), part.getDamageType(), part.getRepairMethod()))
                .toList();

        return new RepairChecklistContext(
                accident.getSnapshotManufacturer(),
                accident.getSnapshotModelName(),
                accident.getSnapshotModelYear(),
                parts);
    }
}
