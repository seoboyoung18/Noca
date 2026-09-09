package com.ssafy.a307.estimatevalidation.entity;

import com.ssafy.a307.estimatevalidation.domain.GeneratedQuestion;
import com.ssafy.a307.estimatevalidation.domain.ValidationFlag;
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
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 검증 결과에서 생성된 확인 질문.
 *
 * <p>{@code @Table(name = ...)} 을 명시한다. Hibernate 기본 명명 전략이 우연히 같은 이름을 만들더라도
 * 그 우연에 기대지 않는다 — 명명 전략은 설정으로 바뀔 수 있고, 그때 조용히 테이블을 잃는다.
 *
 * <p>정본 DDL 의 UNIQUE 두 개를 <b>모두</b> 선언한다. {@code ddl-auto=validate} 는 UNIQUE 를 보지
 * 않으므로 하나를 빠뜨려도 기동은 되지만, 엔티티만 읽는 사람이 정본에 없는 중복 허용을 가정하게
 * 된다(answer24 F-6).
 * <ul>
 *   <li>{@code uk_evq_item_flag} — 한 항목에 같은 플래그의 질문이 두 번 붙지 않는다</li>
 *   <li>{@code uk_evq_order} — 한 검증 안에서 표시 순서가 겹치지 않는다</li>
 * </ul>
 */
@Entity
@Table(
        name = "estimate_validation_question",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_evq_item_flag",
                        columnNames = {"validation_item_id", "source_flag"}),
                @UniqueConstraint(
                        name = "uk_evq_order",
                        columnNames = {"validation_id", "display_order"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EstimateValidationQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "question_id")
    private Long questionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "validation_id", nullable = false)
    private EstimateValidation validation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "validation_item_id")
    private EstimateValidationItem validationItem;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_flag", nullable = false, length = 30)
    private ValidationFlag sourceFlag;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    @Column(name = "question_text", nullable = false, length = 500)
    private String questionText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static EstimateValidationQuestion from(
            EstimateValidation validation,
            EstimateValidationItem item,
            GeneratedQuestion generated,
            Instant createdAt) {
        EstimateValidationQuestion question = new EstimateValidationQuestion();
        question.validation = validation;
        question.validationItem = item;
        question.sourceFlag = generated.sourceFlag();
        question.displayOrder = (short) generated.displayOrder();
        question.questionText = generated.text();
        question.createdAt = createdAt;
        return question;
    }
}
