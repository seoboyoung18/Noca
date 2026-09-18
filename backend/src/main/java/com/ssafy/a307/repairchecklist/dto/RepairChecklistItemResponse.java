package com.ssafy.a307.repairchecklist.dto;

import com.ssafy.a307.repairchecklist.entity.RepairChecklistItem;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistItemCategory;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistItemSource;

import java.time.Instant;

/**
 * 체크리스트 항목 한 줄 (S15P21A307-483 · 부위·분류 -544).
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
 * <h2>부위·분류를 함께 준다 (S15P21A307-544)</h2>
 *
 * <p>화면이 공통 / 부품별 / 함께 점검 세 탭을 그린다. {@link #category} 가 그 축이고,
 * {@link #source} 와 <b>다른 축이다</b> — 사용자가 직접 넣은 항목도 부품별일 수 있다.
 *
 * <p><b>{@link #partNameKo} 는 저장된 값이 아니다.</b> 조회할 때 {@code part_code} 마스터에서
 * 붙인다 — 견적 항목과 같은 방식이다. 마스터에 없는 코드는 애초에 저장되지 않지만
 * ({@code part_code} FK), 이름을 못 찾으면 {@code null} 로 두고 조회를 죽이지 않는다.
 *
 * @param category   {@code COMMON} · {@code PART} · {@code HIDDEN}
 * @param partCode   그 항목이 가리키는 부위. 부위와 무관하면 {@code null}
 * @param partNameKo 한글 부위명. {@code partCode} 가 없으면 {@code null}
 * @param reason     {@code HIDDEN} 인 이유 한 문장. 나머지는 {@code null}
 * @param checkedAt  체크한 시각. 체크하지 않았으면 {@code null} 이다
 *                   ({@code ck_rcli_checked} 가 그 조합만 허용한다)
 * @param memo       사용자 메모. 없으면 {@code null}. <b>체크 여부와 무관하다</b>
 */
public record RepairChecklistItemResponse(
        Long itemId,
        RepairChecklistItemSource source,
        RepairChecklistItemCategory category,
        String commonCode,
        String partCode,
        String partNameKo,
        String content,
        String reason,
        boolean checked,
        String memo,
        short displayOrder,
        Instant checkedAt) {

    /**
     * @param partNameKo 마스터에서 찾은 한글 부위명. <b>호출자가 넘긴다</b> — 이 record 가
     *                   마스터를 읽으면 항목마다 질의가 나간다. 부위가 없으면 {@code null}
     */
    public static RepairChecklistItemResponse from(RepairChecklistItem item, String partNameKo) {
        return new RepairChecklistItemResponse(
                item.getItemId(),
                item.getSource(),
                item.getCategory(),
                item.getCommonCode(),
                item.getPartCode(),
                item.getPartCode() == null ? null : partNameKo,
                item.getContent(),
                item.getReason(),
                item.isChecked(),
                item.getMemo(),
                item.getDisplayOrder(),
                item.getCheckedAt());
    }
}
