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

@Entity
@Table(
        name = "estimate_validation_question",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_evq_item_flag", columnNames = {"validation_item_id", "source_flag"}))
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
