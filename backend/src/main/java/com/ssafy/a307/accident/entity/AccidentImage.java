package com.ssafy.a307.accident.entity;

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
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 사고 이미지 한 장. 경로·크기 메타는 이 행이 아니라 {@link AccidentImageAsset} 에 있다.
 * <p>
 * 행은 <b>업로드 URL 발급 시점에 먼저 생긴다.</b> presigned 직접 업로드에서는 서버가 바이트를
 * 받지 않으므로, 키를 정하려면 {@code image_id} 가 먼저 있어야 한다. 그래서 asset 이 없는
 * {@code accident_image} 행은 발급됐지만 아직 완료 통보를 받지 못한 파일을 뜻한다
 * (업로드 상태 판정 D2-A — {@code upload_status} 컬럼을 두지 않은 이유다).
 * <p>
 * <b>각도 태그를 저장하지 않는다.</b> 정본 DDL 의 {@code accident_image} 에 {@code angle_tag} 가
 * 없다 — {@code repair_case_image.angle_tag}(학습 사례 이미지)에만 있다. 컬럼 추가는 스키마 변경이라
 * 사용자 확인 대기 항목으로 남겼다(answer25 D1).
 */
@Entity
@Table(name = "accident_image")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccidentImage {

    /** {@code quality_reason} 은 VARCHAR(100) 이다. 넘치면 INSERT 가 깨지므로 여기서 자른다. */
    public static final int MAX_QUALITY_REASON_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "image_id")
    private Long imageId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "accident_id", nullable = false, updatable = false)
    private Accident accident;

    @Column(name = "original_filename", nullable = false, length = 255, updatable = false)
    private String originalFilename;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_status", nullable = false, length = 20)
    private ImageQualityStatus qualityStatus;

    @Column(name = "quality_reason", length = 100)
    private String qualityReason;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * 한 이미지의 asset 저장은 원자적이어야 한다 — {@code ORIGINAL} 은 있는데 {@code RESIZED} 가
     * 없는 중간 상태를 커밋하지 않는다. 그래서 컬렉션으로 묶어 한 번에 저장한다.
     */
    @OneToMany(mappedBy = "image", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("assetId asc")
    private List<AccidentImageAsset> assets = new ArrayList<>();

    private AccidentImage(Accident accident, String originalFilename) {
        this.accident = accident;
        this.originalFilename = originalFilename;
        this.qualityStatus = ImageQualityStatus.PASS;
    }

    /** 업로드 URL 발급 시점에 예약되는 행. 품질은 판정 전이므로 정본 DEFAULT 와 같은 PASS 다. */
    public static AccidentImage reserve(Accident accident, String originalFilename) {
        return new AccidentImage(accident, originalFilename);
    }

    public List<AccidentImageAsset> getAssets() {
        return Collections.unmodifiableList(assets);
    }

    public void addAsset(AccidentImageAsset asset) {
        assets.add(asset);
        asset.attachTo(this);
    }

    public Optional<AccidentImageAsset> asset(ImageVariant variant) {
        return assets.stream().filter(a -> a.getVariant() == variant).findFirst();
    }

    public boolean hasVariant(ImageVariant variant) {
        return asset(variant).isPresent();
    }

    /** 원본이 저장소에 올라와 처리까지 끝났는지. 업로드 상태 판정의 유일한 근거다(D2-A). */
    public boolean isUploadCompleted() {
        return hasVariant(ImageVariant.ORIGINAL);
    }

    /**
     * 품질 판정 결과를 반영한다. {@code PASS} 면 사유를 남기지 않는다 —
     * 통과한 이미지에 사유가 붙어 있으면 화면이 재촬영 권유를 잘못 그린다.
     */
    public void markQuality(ImageQualityStatus status, String reason) {
        this.qualityStatus = status;
        this.qualityReason = status == ImageQualityStatus.WARN ? truncate(reason) : null;
    }

    private static String truncate(String reason) {
        if (reason == null || reason.isBlank()) return null;
        String stripped = reason.strip();
        return stripped.length() <= MAX_QUALITY_REASON_LENGTH
                ? stripped
                : stripped.substring(0, MAX_QUALITY_REASON_LENGTH);
    }
}
