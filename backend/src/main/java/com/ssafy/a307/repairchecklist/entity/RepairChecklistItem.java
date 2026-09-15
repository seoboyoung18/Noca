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
 * <p><b>체크와 메모는 서로 묶여 있지 않다</b>({@code S15P21A307-483}). {@code memo} 는 nullable
 * 이고 {@code is_checked} 와 제약으로 묶이지 않아, 체크하지 않은 항목에도 메모를 남길 수 있다.
 * 정비소에서 "이건 아직 못 물어봤는데 이렇게 답했다" 를 적는 자리가 필요하다.
 *
 * <p><b>{@code ck_rcli_checked} ({@code checked_at IS NULL OR is_checked = TRUE}) 를 지키는 곳은
 * 이 클래스 하나다.</b> 체크는 두 열을 함께 쓰고, 해제는 {@code checked_at} 을 함께 비운다 —
 * 서비스가 열을 따로 만지게 두면 어느 한 경로에서 제약에 걸려 500 이 난다.
 */
@Entity
@Table(name = "repair_checklist_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepairChecklistItem {

    /** {@code content VARCHAR(500)}. */
    public static final int MAX_CONTENT_LENGTH = 500;

    /** {@code memo VARCHAR(500)}. 넘치면 UPDATE 가 깨져 메모가 통째로 저장되지 않는다. */
    public static final int MAX_MEMO_LENGTH = 500;

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

    /**
     * 완료로 표시한다. <b>{@code is_checked} 와 {@code checked_at} 을 함께 쓴다</b> —
     * {@code ck_rcli_checked} 가 둘의 조합을 강제한다.
     *
     * <p>이미 체크돼 있으면 {@code checked_at} 을 덮지 않는다. 같은 요청이 두 번 와도 처음 체크한
     * 시각이 보존돼야 한다 — 사용자가 언제 확인했는지가 그 열의 뜻이다.
     */
    public void check(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now 는 필수입니다.");
        }
        if (!this.checked) {
            this.checked = true;
            this.checkedAt = now;
        }
        this.updatedAt = now;
    }

    /**
     * 체크를 해제한다. <b>{@code checked_at} 도 함께 비운다.</b>
     *
     * <p>비우지 않으면 {@code ck_rcli_checked} 에 걸려 UPDATE 가 거부된다 —
     * 그 제약은 "체크되지 않았는데 체크 시각이 남아 있는" 행을 허용하지 않는다.
     */
    public void uncheck(Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now 는 필수입니다.");
        }
        this.checked = false;
        this.checkedAt = null;
        this.updatedAt = now;
    }

    /**
     * 메모를 바꾼다. <b>체크 여부를 건드리지 않는다.</b>
     *
     * @param memo {@code null} 이나 공백이면 메모를 지운다 — 열이 nullable 이므로 빈 문자열을
     *             남기지 않는다. 화면에서 "지움" 과 "빈 메모" 를 구분할 이유가 없다
     */
    public void changeMemo(String memo, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now 는 필수입니다.");
        }
        this.memo = (memo == null || memo.isBlank()) ? null : truncate(memo, MAX_MEMO_LENGTH);
        this.updatedAt = now;
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 는 필수입니다.");
        }
        return truncate(content, MAX_CONTENT_LENGTH);
    }

    private static String truncate(String value, int max) {
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }
}
