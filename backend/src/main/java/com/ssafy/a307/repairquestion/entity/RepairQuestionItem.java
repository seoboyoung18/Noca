package com.ssafy.a307.repairquestion.entity;

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
 * 질문 한 줄 (S15P21A307-477).
 *
 * <p><b>{@code content} 는 그대로 복사해 쓰는 완성된 문장이다.</b> {@code S15P21A307-476} 이 개별
 * 복사와 전체 복사를 요구하므로 조각으로 쪼개 두고 화면에서 조립하게 만들지 않는다.
 *
 * <h2>근거 네 열은 통째로 있거나 통째로 없다</h2>
 *
 * <p>정본 DDL 의 두 CHECK 가 그것을 강제한다.
 *
 * <ul>
 *   <li>{@code ck_rqi_basis CHECK ((part_code IS NULL) = (snapshot_part_name IS NULL))}</li>
 *   <li>{@code ck_rqi_part CHECK (part_code IS NOT NULL
 *       OR (damage_type IS NULL AND repair_method IS NULL))}</li>
 * </ul>
 *
 * <p>그래서 정적 팩터리를 둘로 나눴다 — 부품에 매인 질문과 그렇지 않은 질문이다. 호출자가 네 값을
 * 따로 넣을 수 있게 두면 "부품 없이 판정만" 같은 조합이 만들어져 INSERT 가 거부된다.
 *
 * <p><b>{@code snapshot_part_name} 은 생성 시점 {@code part_code.name_ko} 의 사본</b>이다.
 * 마스터가 이름을 고쳐도 이미 만들어진 질문 문안과 근거 표시가 어긋나지 않는다 —
 * {@code accident} 의 {@code snapshot_*} 열과 같은 방식이다.
 *
 * <p><b>{@code updated_at} 이 없다.</b> 질문 항목을 고치는 기능이 없고 재생성은 행을 갈아 끼운다
 * ({@code S15P21A307-510} 이 그 이유로 열을 두지 않았다).
 */
@Entity
@Table(name = "repair_question_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepairQuestionItem {

    /** {@code content VARCHAR(500)}. */
    public static final int MAX_CONTENT_LENGTH = 500;

    /** {@code snapshot_part_name VARCHAR(50)} — {@code part_code.name_ko} 와 같은 길이다. */
    public static final int MAX_PART_NAME_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long itemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private RepairQuestion question;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private RepairQuestionItemSource source;

    @Column(name = "content", nullable = false, length = MAX_CONTENT_LENGTH)
    private String content;

    @Column(name = "part_code", length = 50)
    private String partCode;

    @Column(name = "snapshot_part_name", length = MAX_PART_NAME_LENGTH)
    private String snapshotPartName;

    @Column(name = "damage_type", length = 20)
    private String damageType;

    @Column(name = "repair_method", length = 20)
    private String repairMethod;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private RepairQuestionItem(RepairQuestion question, String content, String partCode,
                               String snapshotPartName, String damageType, String repairMethod,
                               int displayOrder, Instant now) {
        this.question = question;
        this.source = RepairQuestionItemSource.AI;
        this.content = content;
        this.partCode = partCode;
        this.snapshotPartName = snapshotPartName;
        this.damageType = damageType;
        this.repairMethod = repairMethod;
        this.displayOrder = (short) displayOrder;
        this.createdAt = now;
    }

    /**
     * 특정 부품에 매이지 않은 질문. 근거 네 열이 <b>전부 비어 있다</b>.
     *
     * <p>{@code S15P21A307-476} 본문의 "순정 외 부품 사용 시 금액 차이는 얼마인가요?" 가 이 모양이다.
     */
    public static RepairQuestionItem general(RepairQuestion question, String content,
                                             int displayOrder, Instant now) {
        return new RepairQuestionItem(question, requireContent(content),
                null, null, null, null, displayOrder, now);
    }

    /**
     * 부품에 매인 질문. 근거 네 열이 <b>함께</b> 찬다.
     *
     * <p>판정 두 값은 {@code damaged_part} 에서 복사한 것이어야 한다 — LLM 이 정하지 않는다.
     * {@code ck_rqi_damage} · {@code ck_rqi_method} 가 어휘를 강제하므로 지어낸 값은 거부된다.
     *
     * @param partName 생성 시점 {@code part_code.name_ko} 사본. 비면 근거를 담을 수 없다
     */
    public static RepairQuestionItem grounded(RepairQuestion question, String content,
                                              String partCode, String partName,
                                              String damageType, String repairMethod,
                                              int displayOrder, Instant now) {
        if (partCode == null || partCode.isBlank() || partName == null || partName.isBlank()) {
            throw new IllegalArgumentException("근거가 있는 질문은 partCode 와 partName 이 함께 필요합니다.");
        }
        return new RepairQuestionItem(question, requireContent(content),
                partCode.strip(), truncate(partName, MAX_PART_NAME_LENGTH),
                blankToNull(damageType), blankToNull(repairMethod), displayOrder, now);
    }

    private static String requireContent(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 는 필수입니다.");
        }
        return truncate(content, MAX_CONTENT_LENGTH);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String truncate(String value, int max) {
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max);
    }
}
