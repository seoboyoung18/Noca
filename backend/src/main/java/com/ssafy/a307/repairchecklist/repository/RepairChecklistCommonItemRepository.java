package com.ssafy.a307.repairchecklist.repository;

import com.ssafy.a307.repairchecklist.entity.RepairChecklistCommonItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RepairChecklistCommonItemRepository
        extends JpaRepository<RepairChecklistCommonItem, String> {

    /**
     * 체크리스트에 넣을 공통 항목. <b>꺼 둔 항목은 새 체크리스트에 들어가지 않는다.</b>
     *
     * <p>{@code code} 를 2차 정렬 키로 둔다 — {@code display_order} 가 같은 행이 둘 이상이면
     * DB 가 돌려주는 순서에 기대게 되고, 그러면 같은 사고를 두 번 만들 때 항목 순서가 달라진다.
     */
    List<RepairChecklistCommonItem> findByActiveTrueOrderByDisplayOrderAscCodeAsc();
}
