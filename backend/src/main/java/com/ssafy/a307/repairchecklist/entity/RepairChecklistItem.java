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

    /** {@code reason VARCHAR(300)}. {@code HIDDEN} 항목이 "왜 이것도 보라는가" 를 적는 자리다. */
    public static final int MAX_REASON_LENGTH = 300;

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

    /**
     * 무엇에 대한 항목인가. <b>{@code source} 와 다른 축이다</b> — 사용자가 직접 넣은 항목도
     * 부위별일 수 있다. 화면은 이 값으로 세 탭을 가른다.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 10)
    private RepairChecklistItemCategory category;

    /**
     * 그 항목이 가리키는 부위. 부위와 무관하거나 <b>LLM 이 지어낸 코드</b>면 {@code null} 이다.
     *
     * <p>한글 이름을 사본으로 두지 않는다. 화면이 마스터의 현재 이름으로 그룹 제목을 그리므로
     * 이름이 바뀌면 바뀐 이름으로 보이는 편이 맞다 — {@code content} 를 복사해 두는 것과는
     * 다른 판단이고, 그 차이는 문안에 부품 이름이 박히느냐에서 온다.
     */
    @Column(name = "part_code", length = 50)
    private String partCode;

    /** {@code HIDDEN} 인 이유 한 문장. 나머지 분류는 {@code null} 이다. */
    @Column(name = "reason", length = MAX_REASON_LENGTH)
    private String reason;

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
                                String commonCode, String content,
                                RepairChecklistItemCategory category, String partCode,
                                String reason, int displayOrder, Instant now) {
        this.checklist = checklist;
        this.source = source;
        this.commonCode = commonCode;
        this.content = content;
        this.category = category;
        this.partCode = partCode;
        this.reason = reason;
        this.checked = false;
        this.displayOrder = (short) displayOrder;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * LLM 이 만든 사고별 항목. {@code common_code} 는 비운다({@code ck_rcli_link}).
     *
     * @param category {@code PART} 또는 {@code HIDDEN}. {@code null} 이면 {@code PART} 로 둔다 —
     *                 분류를 못 정한 항목 때문에 생성 전체를 버리지 않는다
     * @param partCode <b>마스터에 있는 코드만 온다.</b> 거르는 곳은
     *                 {@code RepairChecklistGenerator} 다
     * @param reason   {@code HIDDEN} 인 이유. 공백이면 {@code null} 로 둔다
     */
    public static RepairChecklistItem ai(RepairChecklist checklist, String content,
                                         RepairChecklistItemCategory category, String partCode,
                                         String reason, int displayOrder, Instant now) {
        return new RepairChecklistItem(checklist, RepairChecklistItemSource.AI, null,
                requireContent(content), categoryOrPart(category), blankToNull(partCode),
                truncateOrNull(reason, MAX_REASON_LENGTH), displayOrder, now);
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
                requireContent(master.getMessage()), RepairChecklistItemCategory.COMMON, null, null,
                displayOrder, now);
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

    /**
     * 사용자가 직접 넣은 항목 (S15P21A307-485).
     *
     * <p><b>{@code source} 를 호출자가 정하지 못한다.</b> 이 팩터리를 거치면 무조건 {@code USER}
     * 이고 {@code common_code} 는 비어 있다 — {@code ck_rcli_link} 가 그 조합만 허용한다.
     * {@code NULL} 은 UNIQUE 를 통과하므로 사용자 항목은 몇 개든 들어간다({@code uk_rcli_common}).
     *
     * @param displayOrder 그 체크리스트의 현재 최댓값 + 1. 맨 뒤에 붙는다 — 사용자가 나중에
     *                     적은 것이 AI·공통 항목 사이에 끼어들면 순서가 뜻을 잃는다
     */
    public static RepairChecklistItem user(RepairChecklist checklist, String content,
                                           RepairChecklistItemCategory category, String partCode,
                                           int displayOrder, Instant now) {
        return new RepairChecklistItem(checklist, RepairChecklistItemSource.USER, null,
                requireContent(content), categoryOrPart(category), blankToNull(partCode),
                null, displayOrder, now);
    }

    /** 사용자가 직접 넣은 항목인가. {@code AI}·{@code COMMON} 은 고치거나 지울 수 없다. */
    public boolean userCreated() {
        return this.source == RepairChecklistItemSource.USER;
    }

    /**
     * 문안을 바꾼다 (S15P21A307-485).
     *
     * <p><b>{@code USER} 항목만 호출해야 한다.</b> 그 판단은 서비스가 하고(400 으로 거절),
     * 여기서는 상태 전이만 한다 — 호출 가능 여부를 두 곳에서 판단하면 한쪽이 늦게 바뀐다.
     *
     * <p>{@code AI}·{@code COMMON} 문안을 고칠 수 없게 한 이유는 {@code content} 가 생성 시점
     * <b>복사본</b>이기 때문이다({@code S15P21A307-509}). 사용자가 AI 문안을 고치면 "그때 AI 가
     * 뭐라고 했었나" 를 되짚을 수 없다.
     */
    public void changeContent(String content, Instant now) {
        if (now == null) {
            throw new IllegalArgumentException("now 는 필수입니다.");
        }
        this.content = requireContent(content);
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

    /**
     * 분류를 못 정했으면 {@code PART} 다.
     *
     * <p>{@code category} 는 {@code NOT NULL} 이라 빈 값으로 둘 수 없고, 셋 중 하나를 골라야
     * 한다면 {@code PART} 다 — 화면의 기본 탭이고, 이 변경 이전에 만들어진 행도 같은 값으로
     * 채워졌다({@code DEFAULT 'PART'}).
     */
    private static RepairChecklistItemCategory categoryOrPart(RepairChecklistItemCategory category) {
        return category == null ? RepairChecklistItemCategory.PART : category;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String truncateOrNull(String value, int max) {
        String stripped = blankToNull(value);
        return stripped == null ? null : truncate(stripped, max);
    }
}
