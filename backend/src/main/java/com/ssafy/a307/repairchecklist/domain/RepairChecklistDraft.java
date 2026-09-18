package com.ssafy.a307.repairchecklist.domain;

import com.ssafy.a307.repairchecklist.entity.RepairChecklistItemCategory;

import java.util.List;

/**
 * LLM 이 만든 체크리스트 초안 (S15P21A307-544).
 *
 * <p><b>엔티티가 아니다.</b> 생성기가 낸 값을 워커가 받아 {@code repair_checklist_item} 으로
 * 옮기는 사이의 모양이고, 그 사이에서 검증이 이미 끝나 있다 — 여기 담긴 {@code partCode} 는
 * 마스터에 있는 값이고, {@code category} 는 세 값 중 하나다.
 *
 * <p>문자열 넷을 나란히 넘기지 않는 이유는 단순하다. {@code content} · {@code partCode} ·
 * {@code reason} 이 전부 {@code String} 이라 자리를 바꿔 넣어도 컴파일이 통과한다.
 *
 * @param summary 한 줄 요약. <b>없으면 {@code null}</b> — 화면이 요약 영역을 그리지 않는다
 * @param items   사고별 항목. 공통 6종은 여기 없다(시드에서 붙인다)
 */
public record RepairChecklistDraft(String summary, List<DraftItem> items) {

    public RepairChecklistDraft {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * @param category {@code PART} 또는 {@code HIDDEN}. {@code COMMON} 은 LLM 이 만들지 않는다
     * @param partCode 마스터에 있는 부품 코드. 부위와 무관하거나 모르는 코드면 {@code null}
     * @param reason   {@code HIDDEN} 인 이유 한 문장. 나머지는 {@code null}
     */
    public record DraftItem(String content, RepairChecklistItemCategory category,
                            String partCode, String reason) {
    }
}
