package com.ssafy.a307.estimatevalidation.entity;

import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.estimatevalidation.domain.EstimateFileType;
import com.ssafy.a307.estimatevalidation.domain.ValidationGrade;
import com.ssafy.a307.estimatevalidation.domain.ValidationStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Lob;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "estimate_validation")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EstimateValidation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "validation_id")
    private Long validationId;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "accident_id", nullable = false)
    private Accident accident;

    @Column(name = "estimate_id")
    private Long estimateId;

    @Column(name = "s3_key_file", length = 500)
    private String s3KeyFile;

    @Enumerated(EnumType.STRING)
    @Column(name = "file_type", nullable = false, length = 10)
    private EstimateFileType fileType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ValidationStatus status;

    @Column(name = "claimed_total")
    private Integer claimedTotal;

    @Column(name = "llm_model", length = 50)
    private String llmModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "llm_grade", length = 20)
    private ValidationGrade llmGrade;

    @Lob
    @Column(name = "llm_summary")
    private String llmSummary;

    @Column(name = "failure_reason", length = 200)
    private String failureReason;

    @Column(name = "review_item_count", nullable = false)
    private int reviewItemCount;

    @Column(name = "total_item_count", nullable = false)
    private int totalItemCount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(mappedBy = "validation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    private final List<EstimateValidationItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "validation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder ASC")
    private final List<EstimateValidationQuestion> questions = new ArrayList<>();

    @OneToOne(mappedBy = "validation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private EstimateValidationReport report;

    private EstimateValidation(
            Long memberId,
            Accident accident,
            Long estimateId,
            String s3KeyFile,
            EstimateFileType fileType,
            ValidationStatus status,
            Integer claimedTotal) {
        this.memberId = memberId;
        this.accident = accident;
        this.estimateId = estimateId;
        this.s3KeyFile = s3KeyFile;
        this.fileType = fileType;
        this.status = status;
        this.claimedTotal = claimedTotal;
    }

    public static EstimateValidation processingManual(
            Long memberId, Accident accident, Long estimateId, int claimedTotal) {
        return new EstimateValidation(
                memberId, accident, estimateId, null, EstimateFileType.MANUAL,
                ValidationStatus.PROCESSING, claimedTotal);
    }

    public static EstimateValidation queuedFile(
            Long memberId, Accident accident, Long estimateId, String storageKey, EstimateFileType fileType) {
        return new EstimateValidation(
                memberId, accident, estimateId, storageKey, fileType,
                ValidationStatus.QUEUED, null);
    }

    public void addItem(EstimateValidationItem item) {
        items.add(item);
    }

    public void addQuestion(EstimateValidationQuestion question) {
        questions.add(question);
    }

    public void initializeReport() {
        this.report = EstimateValidationReport.queued(this);
    }

    public void complete(ValidationGrade grade, String summary, int reviewItemCount, int totalItemCount, Instant now) {
        this.status = ValidationStatus.COMPLETED;
        this.llmGrade = grade;
        this.llmSummary = summary;
        this.reviewItemCount = reviewItemCount;
        this.totalItemCount = totalItemCount;
        this.failureReason = null;
        this.completedAt = now;
    }

    public void fail(String safeReason, Instant now) {
        this.status = ValidationStatus.FAILED;
        this.failureReason = safeReason;
        this.completedAt = now;
    }
}
