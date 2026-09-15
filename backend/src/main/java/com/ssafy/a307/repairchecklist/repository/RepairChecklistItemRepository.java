package com.ssafy.a307.repairchecklist.repository;

import com.ssafy.a307.repairchecklist.entity.RepairChecklistItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RepairChecklistItemRepository extends JpaRepository<RepairChecklistItem, Long> {

    /** {@code ix_rcli_checklist (checklist_id, display_order)} 가 이 조회를 위해 있다. */
    List<RepairChecklistItem> findByChecklist_ChecklistIdOrderByDisplayOrderAscItemIdAsc(Long checklistId);

    /**
     * 체크·메모를 고칠 항목 하나 (S15P21A307-483).
     *
     * <p><b>소유자 조건과 사고 조건이 쿼리 안에 있다.</b> {@code itemId} 만으로 찾은 뒤 자바에서
     * 비교하면 "그 항목은 존재한다" 는 사실이 응답 시간·분기로 새어 나간다. 남의 항목 · 없는
     * 항목 · 다른 사고의 항목이 <b>모두 빈 값</b>으로 돌아오고, 호출자는 셋을 구분하지 말고
     * 404 로 답해야 한다({@code RepairChecklistRepository#findByAccidentIdAndMemberId} 와 같은 판단).
     *
     * <p>{@code accidentId} 까지 조건에 넣는 이유는 경로가 사고 기준이기 때문이다 —
     * {@code /api/accidents/{accidentId}/repair-checklist/items/{itemId}} 에서 두 값이 서로 다른
     * 체크리스트를 가리키면 그 요청은 잘못된 것이고, 받아 주면 경로가 거짓말을 하게 된다.
     *
     * <p>{@code accident} 에 {@code member_id} 가 없으므로 경로는
     * {@code checklist → accident → vehicle → member} 다.
     */
    @Query("""
            select i from RepairChecklistItem i
            join i.checklist c
            join c.accident a
            join a.vehicle v
            where i.itemId = :itemId
              and a.accidentId = :accidentId
              and v.memberId = :memberId
            """)
    Optional<RepairChecklistItem> findOwnedItem(@Param("itemId") Long itemId,
                                                @Param("accidentId") Long accidentId,
                                                @Param("memberId") Long memberId);

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
