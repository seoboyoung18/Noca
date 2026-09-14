package com.ssafy.a307.analysis.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 분석 결과의 이미지 단위 기록 (S15P21A307-157).
 *
 * <p>사진 한 장이 한 행이다. {@code UNIQUE (job_id, image_id)} 가 그것을 강제하므로
 * "어느 이미지의 좌표인가" 가 JSONB 안이 아니라 행으로 해결된다.
 *
 * <h2>{@code detections} 를 {@code String} 으로 든다</h2>
 *
 * <p>계약이 "받은 detections[] 를 키 이름·구조를 바꾸지 말고 그대로 넣습니다" 라고 요구한다.
 * 자바 타입으로 매핑하면 매핑하지 않은 필드가 저장에서 조용히 사라지므로, <b>원문 문자열</b>로
 * 들고 {@link JdbcTypeCode}{@code (SqlTypes.JSON)} 으로 컬럼 타입만 맞춘다. 그러면 방언이
 * PostgreSQL 의 {@code jsonb} 와 H2 의 {@code json} 을 각각 고른다 — 네이티브 쿼리로 쓰면
 * {@code CAST(? AS JSONB)} 와 {@code CAST(? AS JSON)} 이 달라 한 문장으로 못 쓴다.
 *
 * <p>읽는 쪽은 {@code EstimateReportRepository} 가 이미 네이티브 쿼리로 가져간다. 이 엔티티는
 * <b>쓰기 전용</b>이며 조회 경로를 새로 만들지 않는다.
 *
 * <p>{@code s3_key_overlay} 를 매핑하지 않는다. 오버레이는 2026-09-11 에 폐기됐고 NULL 로 둔다 —
 * 매핑하면 "언젠가 쓸 값" 처럼 보인다.
 */
@Entity
@Table(name = "analysis_image_result")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalysisImageResult {

    /** {@code exclusion_reason VARCHAR(50)}. 계약 값은 NOT_VEHICLE · RATIO_BELOW_THRESHOLD 다. */
    public static final int MAX_EXCLUSION_REASON_LENGTH = 50;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "result_id")
    private Long resultId;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "image_id", nullable = false)
    private Long imageId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detections")
    private String detections;

    @Column(name = "is_excluded", nullable = false)
    private boolean excluded;

    @Column(name = "exclusion_reason", length = MAX_EXCLUSION_REASON_LENGTH)
    private String exclusionReason;

    private AnalysisImageResult(Long jobId, Long imageId, String detections,
                                boolean excluded, String exclusionReason) {
        this.jobId = jobId;
        this.imageId = imageId;
        this.detections = detections;
        this.excluded = excluded;
        this.exclusionReason = exclusionReason;
    }

    /**
     * 계약 {@code imageResults[]} 한 원소를 기록한다.
     *
     * <p>{@code exclusionReason} 이 길면 잘라 넣는다 — 제외 사유 문자열이 길다는 이유로 분석
     * 결과 전체를 버리는 것은 균형이 맞지 않는다. CHECK 가 허용하는 두 값은 훨씬 짧다.
     *
     * @param detections AI 가 보낸 배열 원문. 없으면 {@code null}
     */
    public static AnalysisImageResult of(Long jobId, Long imageId, String detections,
                                         boolean excluded, String exclusionReason) {
        if (jobId == null) {
            throw new IllegalArgumentException("jobId 는 필수입니다.");
        }
        if (imageId == null) {
            throw new IllegalArgumentException("imageId 는 필수입니다.");
        }
        return new AnalysisImageResult(jobId, imageId, detections, excluded,
                truncate(exclusionReason));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.length() <= MAX_EXCLUSION_REASON_LENGTH
                ? stripped
                : stripped.substring(0, MAX_EXCLUSION_REASON_LENGTH);
    }
}
