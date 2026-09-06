package com.ssafy.a307.estimatevalidation.entity;

import com.ssafy.a307.estimatevalidation.domain.EstimateLine;
import com.ssafy.a307.estimatevalidation.domain.ValidationFlag;
import com.ssafy.a307.estimatevalidation.domain.ValidatedLine;
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

import java.util.Comparator;

@Entity
@Table(name = "estimate_validation_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EstimateValidationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "validation_item_id")
    private Long validationItemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "validation_id", nullable = false)
    private EstimateValidation validation;

    @Column(name = "line_no", nullable = false)
    private short lineNo;

    @Column(name = "raw_item_name", nullable = false, length = 200)
    private String rawItemName;

    @Column(name = "normalized_item_name", length = 100)
    private String normalizedItemName;

    @Column(name = "part_code", length = 50)
    private String partCode;

    @Column(name = "work_type", length = 20)
    private String workType;

    @Column(name = "quantity", nullable = false)
    private short quantity;

    @Column(name = "part_cost")
    private Integer partCost;

    @Column(name = "labor_cost")
    private Integer laborCost;

    @Column(name = "subtotal")
    private Integer subtotal;

    @Enumerated(EnumType.STRING)
    @Column(name = "llm_flag", length = 30)
    private ValidationFlag llmFlag;

    @Column(name = "llm_reason", length = 300)
    private String llmReason;

    @Column(name = "reference_min")
    private Integer referenceMin;

    @Column(name = "reference_median")
    private Integer referenceMedian;

    @Column(name = "reference_p75")
    private Integer referenceP75;

    @Column(name = "reference_max")
    private Integer referenceMax;

    @Column(name = "reference_case_count")
    private Integer referenceCaseCount;

    public static EstimateValidationItem from(
            EstimateValidation validation,
            ValidatedLine validated,
            ReferenceSnapshot reference,
            String reason) {
        EstimateLine line = validated.line();
        EstimateValidationItem item = new EstimateValidationItem();
        item.validation = validation;
        item.lineNo = Math.toIntExact(line.lineNo()) > Short.MAX_VALUE
                ? Short.MAX_VALUE : (short) line.lineNo();
        item.rawItemName = line.rawItemName();
        item.normalizedItemName = line.standardPartName();
        item.partCode = line.partCode();
        item.workType = line.workType().displayName();
        item.quantity = Math.toIntExact(line.quantity()) > Short.MAX_VALUE
                ? Short.MAX_VALUE : (short) line.quantity();
        item.partCost = Math.toIntExact(line.partCost());
        item.laborCost = Math.toIntExact(line.laborCost());
        item.subtotal = Math.toIntExact(line.subtotal());
        item.llmFlag = validated.flags().stream()
                .min(Comparator.comparingInt(ValidationFlag::questionOrder))
                .orElse(null);
        item.llmReason = reason;
        if (reference != null) {
            item.referenceMin = reference.costMin();
            item.referenceMedian = reference.costMedian();
            item.referenceP75 = reference.costP75();
            item.referenceMax = reference.costMax();
            item.referenceCaseCount = reference.caseCount();
        }
        return item;
    }
}
