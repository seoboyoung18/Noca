package com.ssafy.a307.repairchecklist.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 체크리스트 항목 한 줄 (S15P21A307-463).
 *
 * <p><b>{@code content} 는 복사본이다.</b> 공통 항목이어도 마스터를 참조해 조회 시점에 문안을
 * 끌어오지 않는다 — 마스터를 고쳐도 이미 만들어진 체크리스트는 그대로 남아야 한다
 * ({@code -509} 가 {@code accident} 스냅샷 열과 같은 방식으로 정해 둔 것이다).
 *
 * <p><b>{@code common_code} 는 {@code COMMON} 일 때만 채운다</b> — {@code ck_rcli_link} 가
 * 양방향으로 강제하므로 한쪽만 맞추면 INSERT 가 거부된다. 정적 팩터리를 둘로 나눈 이유가 그것이다.
 *
 * <p>{@code is_checked}·{@code memo} 를 이 작업에서 옮기지 않는다 —
 * 체크({@code S15P21A307-483})와 메모({@code -482})는 각자의 스토리다. {@code ck_rcli_checked}
 * ({@code checked_at} 은 {@code is_checked=TRUE} 일 때만)를 지켜야 하므로 전이 메서드는 그쪽에서
 * 한 곳에만 둔다.
 */
@Entity
@Table(name = "repair_checklist_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepairChecklistItem {

    /** {@code content VARCHAR(500)}. */
    public static final int MAX_CONTENT_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long itemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "checklist_id", nullable = false)
    private RepairChecklist checklist;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private RepairChecklistItemSource source;

    @Column(name = "common_code", length = 30)
    private String commonCode;

    @Column(name = "content", nullable = false, length = MAX_CONTENT_LENGTH)
    private String content;

    @Column(name = "is_checked", nullable = false)
    private boolean checked;

    @Column(name = "memo", length = 500)
    private String memo;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    @Column(name = "checked_at")
    private Instant checkedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private RepairChecklistItem(RepairChecklist checklist, RepairChecklistItemSource source,
                                String commonCode, String content, int displayOrder, Instant now) {
        this.checklist = checklist;
        this.source = source;
        this.commonCode = commonCode;
        this.content = content;
        this.checked = false;
        this.displayOrder = (short) displayOrder;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** LLM 이 만든 사고별 항목. {@code common_code} 는 비운다({@code ck_rcli_link}). */
    public static RepairChecklistItem ai(RepairChecklist checklist, String content,
                                         int displayOrder, Instant now) {
        return new RepairChecklistItem(checklist, RepairChecklistItemSource.AI, null,
                requireContent(content), displayOrder, now);
    }

    /**
     * 공통 항목. 마스터의 {@code message} 를 {@code content} 에 <b>복사</b>해 넣는다.
     *
     * @param master 마스터 행. 문안은 여기서만 온다 — 호출자가 문자열을 지어내지 않는다
     */
    public static RepairChecklistItem common(RepairChecklist checklist,
                                             RepairChecklistCommonItem master,
                                             int displayOrder, Instant now) {
        if (master == null) {
            throw new IllegalArgumentException("공통 항목 마스터는 필수입니다.");
        }
        return new RepairChecklistItem(checklist, RepairChecklistItemSource.COMMON, master.getCode(),
                requireContent(master.getMessage()), displayOrder, now);
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 는 필수입니다.");
        }
        String stripped = content.strip();
        return stripped.length() <= MAX_CONTENT_LENGTH
                ? stripped
                : stripped.substring(0, MAX_CONTENT_LENGTH);
    }
}
