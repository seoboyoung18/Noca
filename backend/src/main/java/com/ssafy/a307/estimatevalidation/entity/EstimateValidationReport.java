package com.ssafy.a307.estimatevalidation.entity;

import com.ssafy.a307.estimatevalidation.domain.ValidationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "estimate_validation_report")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EstimateValidationReport {

    @Id
    @Column(name = "validation_id")
    private Long validationId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "validation_id")
    private EstimateValidation validation;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ValidationStatus status;

    @Column(name = "s3_key_pdf", length = 500)
    private String s3KeyPdf;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "retry_count", nullable = false)
    private short retryCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public static EstimateValidationReport queued(EstimateValidation validation) {
        EstimateValidationReport report = new EstimateValidationReport();
        report.validation = validation;
        report.status = ValidationStatus.QUEUED;
        report.retryCount = 0;
        report.createdAt = Instant.now();
        return report;
    }
}
