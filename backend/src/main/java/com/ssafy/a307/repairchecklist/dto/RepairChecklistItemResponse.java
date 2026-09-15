package com.ssafy.a307.repairchecklist.dto;

import com.ssafy.a307.repairchecklist.entity.RepairChecklistItem;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistItemSource;

import java.time.Instant;

/**
 * 체크리스트 항목 한 줄 (S15P21A307-483).
 *
 * <p><b>{@link #source} 를 코드로 준다.</b> {@code AI} · {@code COMMON} · {@code USER} 는 DDL 의
 * {@code ck_rcli_source} 표기 그대로이고, 화면 문구는 FE 가 정한다 — 서버가 한글 라벨을 박으면
 * 표현을 바꿀 때마다 배포해야 한다({@code AnalysisProgressResponse} 와 같은 규칙).
 *
 * <p>{@link #commonCode} 는 {@code COMMON} 항목에만 있다({@code ck_rcli_link}). 화면이 공통
 * 항목을 따로 묶어 보여 주려면 이 값이 필요하다 — {@code source} 만으로도 갈리지만, 어느 공통
 * 항목인지는 코드로만 알 수 있다.
 *
 * <p>{@link #content} 는 생성 시점 문안의 <b>복사본</b>이다. 마스터를 고쳐도 이미 만들어진
 * 체크리스트는 그대로 남는다({@code S15P21A307-509} 의 설계).
 *
 * @param checkedAt 체크한 시각. 체크하지 않았으면 {@code null} 이다
 *                  ({@code ck_rcli_checked} 가 그 조합만 허용한다)
 * @param memo      사용자 메모. 없으면 {@code null}. <b>체크 여부와 무관하다</b>
 */
public record RepairChecklistItemResponse(
        Long itemId,
        RepairChecklistItemSource source,
        String commonCode,
        String content,
        boolean checked,
        String memo,
        short displayOrder,
        Instant checkedAt) {

    public static RepairChecklistItemResponse from(RepairChecklistItem item) {
        return new RepairChecklistItemResponse(
                item.getItemId(),
                item.getSource(),
                item.getCommonCode(),
                item.getContent(),
                item.isChecked(),
                item.getMemo(),
                item.getDisplayOrder(),
                item.getCheckedAt());
    }
}
