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
     * <b>생성분만</b> 비운다 — {@code AI}·{@code COMMON} 을 지우고 {@code USER} 는 남긴다.
     * 실패한 건을 다시 만들 때(재시도) 앞 시도가 남긴 항목이 섞이지 않게 하고, 재생성
     * ({@code S15P21A307-486})이 사용자 항목을 보존하게 한다.
     *
     * <p><b>{@code USER} 를 남기는 것이 {@code -485}·{@code -486} 의 핵심 판단이다.</b> 전부
     * 지우는 {@code deleteAllByChecklistId} 가 {@code -460} 시절에 있었지만, 그 주석이 "재생성이
     * 붙을 때는 사용자 항목을 어떻게 할지 그쪽에서 정해야 한다 — 이 메서드를 그대로 쓰면
     * 지워진다" 고 미뤄 두었다. 이 메서드가 그 답이고, 사용자 데이터를 조용히 지우는 메서드를
     * 남겨 두지 않으려고 <b>그쪽을 지웠다</b>(호출처가 없었다).
     *
     * <p>지우는 곳이 둘이라 <b>둘 다 같은 규칙이어야</b> 한다 — 재생성이 사용자 항목을 남기고
     * {@code QUEUED} 로 되돌리는데, 그 건을 집은 {@code RepairChecklistProcessor} 가 전부 지우면
     * 방금 남겨 둔 항목이 몇 초 뒤에 사라진다. 그래서 워커 쪽도 이 메서드를 부른다.
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
     * @return 지운 행 수
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from RepairChecklistItem i where i.checklist.checklistId = :checklistId"
            + " and i.source <> com.ssafy.a307.repairchecklist.entity.RepairChecklistItemSource.USER")
    int deleteGeneratedByChecklistId(@Param("checklistId") Long checklistId);

    /**
     * 그 체크리스트의 {@code display_order} 최댓값. 항목이 없으면 빈 값이다.
     *
     * <p>사용자 항목은 <b>맨 뒤</b>에 붙는다(최댓값 + 1). AI·공통 항목 사이에 끼워 넣지 않는
     * 이유는 {@code RepairChecklistProcessor} 가 "AI 먼저, 공통 뒤" 로 매긴 순서가 기획의
     * 판단이기 때문이다 — 그 사이에 끼면 그 순서가 뜻을 잃는다.
     */
    @Query("select max(i.displayOrder) from RepairChecklistItem i"
            + " where i.checklist.checklistId = :checklistId")
    Optional<Short> findMaxDisplayOrder(@Param("checklistId") Long checklistId);
}
