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

    /** {@code failure_reason VARCHAR(200)}. 넘기면 INSERT 가 깨진다. */
    public static final int MAX_FAILURE_REASON_LENGTH = 200;
    /** {@code llm_model VARCHAR(50)}. */
    public static final int MAX_MODEL_LENGTH = 50;

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

    /**
     * <b>이름과 달리 LLM 이 채우는 값이 아니다.</b> 지금 이 열에 값을 쓰는 코드는 한 줄도 없어
     * 항상 null 이다. 열 이름은 정본 DDL({@code A307_ddl_final.sql}) 을 그대로 따른 것이고,
     * 나중에 모델을 붙이면 그때 어떤 모델을 썼는지 남길 자리로 비워 두었다.
     * 응답에 싣거나 "AI 가 판정했다"는 근거로 쓰지 말 것.
     */
    @Column(name = "llm_model", length = 50)
    private String llmModel;

    @Enumerated(EnumType.STRING)
    @Column(name = "llm_grade", length = 20)
    private ValidationGrade llmGrade;

    /**
     * <b>이름과 달리 LLM 이 만든 문장이 아니다.</b> {@code EstimateValidationService.summary(...)} 가
     * 등급·검토 권장 건수·항목 수·중앙값 차액을 {@code String.format} 으로 조립한 결정적 문자열이다.
     * 같은 입력이면 언제나 같은 문장이 나오고, 외부 모델 호출은 없다. {@code llmGrade} 도 마찬가지로
     * 규칙 엔진({@code gradeDecider})이 정한다. 열 이름은 정본 DDL 을 따른 것일 뿐이므로,
     * 이 값을 근거로 "AI 요약" 이라고 화면에 표기하면 사실과 다르다.
     *
     * <p>실제 컬럼은 PostgreSQL {@code TEXT} · H2 {@code VARCHAR} 로 길이 제한이 없다.
     * <p>
     * <b>{@code @Lob} 을 붙이면 안 된다.</b> Hibernate 6 부터 {@code @Lob} 이 붙은 String 은
     * JDBC {@code CLOB} 으로 해석되고, PostgreSQL 에서 {@code CLOB} 은 {@code oid}(Large Object)로
     * 매핑된다. 기준 DDL 이 {@code TEXT} 라 {@code ddl-auto=validate} 가 기동을 막는다.
     * 요약 텍스트에 Large Object 는 과하기도 하다 — 값이 {@code pg_largeobject} 에 따로 들어가
     * 조회하면 숫자 OID 만 보이고, 행을 지워도 고아 객체가 남는다. (S15P21A307-417)
     * <p>
     * {@code length} 를 적지 않은 것도 의도다. 지정이 없으면 Hibernate 는 {@code varchar(255)} 로
     * 보지만 그 값은 <b>DDL 생성에만</b> 쓰이고, 이 프로젝트는 DDL 을 손으로 관리하며
     * {@code validate} 만 쓴다. 저장 시점에는 길이를 검사하지 않아 255자를 넘겨도 그대로 들어간다
     * (1000자 저장·조회 실측). 다만 누군가 {@code ddl-auto=update} 로 스키마를 만들면
     * 그때는 255 가 실제 제한이 되므로, 그 설정을 쓰지 않는다.
     */
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

    /**
     * {@code QUEUED} → {@code PROCESSING}. 워커가 큐에서 건을 선점할 때 쓴다.
     *
     * <p><b>{@code completedAt} 을 건드리지 않는다.</b> {@code ck_ev_done} 이
     * "completed_at 이 있으면 상태는 COMPLETED 나 FAILED" 를 강제하므로, 여기서 시각을 찍으면
     * PROCESSING 인 채 completed_at 이 있는 행이 되어 CHECK 위반으로 UPDATE 가 깨진다.
     */
    public void markProcessing() {
        this.status = ValidationStatus.PROCESSING;
    }

    public void complete(ValidationGrade grade, String summary, int reviewItemCount, int totalItemCount, Instant now) {
        complete(grade, summary, reviewItemCount, totalItemCount, this.claimedTotal, now);
    }

    /**
     * 총액까지 함께 확정하는 완료. <b>파일 입력 경로에 필요하다.</b>
     *
     * <p>{@link #queuedFile} 은 {@code claimedTotal} 을 null 로 만든다 — 접수 시점에는 문서를
     * 아직 읽지 않았으므로 총액을 알 수 없다. 그 값을 채울 수단이 이것뿐이다.
     * 직접 입력 경로는 접수 시점에 이미 총액이 있어 같은 값을 다시 넣게 되므로 동작이 같다.
     */
    public void complete(ValidationGrade grade, String summary, int reviewItemCount, int totalItemCount,
                         Integer claimedTotal, Instant now) {
        this.status = ValidationStatus.COMPLETED;
        this.llmGrade = grade;
        this.llmSummary = summary;
        this.claimedTotal = claimedTotal;
        this.reviewItemCount = reviewItemCount;
        this.totalItemCount = totalItemCount;
        this.failureReason = null;
        this.completedAt = now;
    }

    /** 요약을 낸 모델 이름. {@code llm_model} 은 VARCHAR(50) 이라 넘치면 자른다. */
    public void recordSummaryModel(String model) {
        if (model == null || model.isBlank()) return;
        String stripped = model.strip();
        this.llmModel = stripped.length() > MAX_MODEL_LENGTH
                ? stripped.substring(0, MAX_MODEL_LENGTH) : stripped;
    }

    /**
     * 검증을 실패로 종결한다.
     *
     * <p><b>{@code safeReason} 은 사용자에게 그대로 보인다</b>({@code ValidationStatusResponse.failureReason}).
     * 내부 예외 메시지·스택·저장소 키·API 키를 넣으면 안 된다. 열이 VARCHAR(200) 이라
     * 넘치는 값은 여기서 자른다 — 자르지 않으면 INSERT 가 깨져 실패조차 기록되지 않는다.
     */
    public void fail(String safeReason, Instant now) {
        this.status = ValidationStatus.FAILED;
        this.failureReason = truncate(safeReason, MAX_FAILURE_REASON_LENGTH);
        this.completedAt = now;
    }

    private static String truncate(String value, int limit) {
        if (value == null) return null;
        String stripped = value.strip();
        return stripped.length() > limit ? stripped.substring(0, limit) : stripped;
    }
}
