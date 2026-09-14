package com.ssafy.a307.repairchecklist.repository;

import com.ssafy.a307.repairchecklist.entity.RepairChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RepairChecklistItemRepository extends JpaRepository<RepairChecklistItem, Long> {

    /** {@code ix_rcli_checklist (checklist_id, display_order)} 가 이 조회를 위해 있다. */
    List<RepairChecklistItem> findByChecklist_ChecklistIdOrderByDisplayOrderAscItemIdAsc(Long checklistId);

    /**
     * 생성분을 통째로 비운다. 실패한 건을 다시 만들 때 앞 시도가 남긴 항목이 섞이지 않게 한다.
     *
     * <p><b>파생 {@code deleteBy...} 가 아니라 벌크 DELETE 여야 한다.</b> 파생 삭제는 엔티티를
     * 하나씩 {@code remove} 표시만 해 두고 실제 DELETE 는 플러시 때 나가는데, <b>Hibernate 는
     * 같은 플러시에서 INSERT 를 DELETE 보다 먼저 내보낸다.</b> 그러면 앞 시도의 공통 항목이 아직
     * 남은 채 새 공통 항목이 들어가 {@code uk_rcli_common (checklist_id, common_code)} 을
     * 위반하고, 재시도가 통째로 실패한다. 2026-09-14 에 실제로 그렇게 깨졌다.
     *
     * <p>벌크 DELETE 는 호출 즉시 한 문장으로 나가므로 순서 문제가 없다.
     * {@code clearAutomatically} 는 쓰지 않는다 — 영속성 컨텍스트를 비우면 호출자가 들고 있던
     * {@code RepairChecklist} 가 준영속이 되어 이어지는 상태 전이가 저장되지 않는다.
     *
     * <p><b>{@code USER} 항목까지 지운다.</b> 지금은 생성이 {@code COMPLETED} 가 아닌 건에만
     * 일어나므로 사용자가 손으로 넣은 항목({@code S15P21A307-485})이 있을 수 없다. 완성된
     * 체크리스트를 다시 만드는 재생성({@code -486})이 붙을 때는 사용자 항목을 어떻게 할지
     * 그쪽에서 정해야 한다 — 이 메서드를 그대로 쓰면 지워진다.
     *
     * @return 지운 행 수
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from RepairChecklistItem i where i.checklist.checklistId = :checklistId")
    int deleteAllByChecklistId(@Param("checklistId") Long checklistId);
}
