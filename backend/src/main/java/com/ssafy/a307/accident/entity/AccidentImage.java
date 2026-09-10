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
 * <b>촬영 각도는 {@code angle_code} 에 저장한다 — 정정.</b> 원래 이 Javadoc 은 "각도 태그를
 * 저장하지 않는다(answer25 D1)" 고 적었고 실제로 컬럼이 없어서, 발급 요청에 담겨 온
 * {@code angleCode} 가 응답으로 되돌아가기만 하고 사라졌다. 화면을 새로 고치면 각도 라벨이
 * 없어지는 문제라 {@code S15P21A307-137} 에서 컬럼을 추가했다
 * ({@code Docs/Erd/migrations/2026-09-10-accident-image-angle-code.sql}).
 * <p>
 * {@code repair_case_image.angle_tag}(학습 사례 이미지)와 <b>이름을 일부러 다르게 뒀다.</b>
 * 값 집합도 쓰임새도 다르고, API 필드명이 {@code angleCode} 라 그쪽에 맞췄다.
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

    /**
     * 촬영 각도. {@code GET /api/guides/shooting} 의 {@code shots[].angleCode} 가 정본이고
     * {@code ShootingAngleCodes} 가 발급 시점에 검증한다.
     * <p>
     * <b>nullable 이다.</b> 컬럼이 생기기 전에 올라간 이미지는 각도를 알 방법이 없고, 촬영
     * 가이드를 건너뛴 업로드도 각도가 없는 것이 정상이다. 추측해서 채우지 않는다.
     * <p>
     * <b>{@code updatable = false} 다.</b> 각도 수정 API 는 요구사항에 없다. 바꾸려면 지우고
     * 다시 올리는 것이 현재 계약이므로, 실수로 UPDATE 가 나가지 않게 매핑으로 막는다.
     */
    @Column(name = "angle_code", length = 20, updatable = false)
    private String angleCode;

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

    private AccidentImage(Accident accident, String originalFilename, String angleCode) {
        this.accident = accident;
        this.originalFilename = originalFilename;
        this.angleCode = angleCode;
        this.qualityStatus = ImageQualityStatus.PASS;
    }

    /**
     * 업로드 URL 발급 시점에 예약되는 행. 품질은 판정 전이므로 정본 DEFAULT 와 같은 PASS 다.
     * <p>
     * <b>각도를 여기서 함께 넣는다.</b> {@code angleCode} 는 발급 요청에만 담겨 오고 완료 통보
     * 요청에는 없다. 완료 시점에 저장하려면 같은 값을 한 번 더 받아야 하고, 그러면 두 값이
     * 어긋나는 상태가 생긴다. 예약 INSERT 하나에 같이 들어가므로 <b>행과 각도가 따로 저장되어
     * 반쪽만 남는 경우가 없다.</b>
     *
     * @param angleCode 촬영 가이드의 각도 코드. 없으면 {@code null} — 빈 문자열은 null 로 접는다
     */
    public static AccidentImage reserve(Accident accident, String originalFilename, String angleCode) {
        return new AccidentImage(accident, originalFilename, normalizeAngleCode(angleCode));
    }

    /** 각도를 모르는 업로드. 빈 문자열과 {@code null} 을 한 가지 상태로 모은다. */
    public static AccidentImage reserve(Accident accident, String originalFilename) {
        return reserve(accident, originalFilename, null);
    }

    private static String normalizeAngleCode(String angleCode) {
        if (angleCode == null) return null;
        String stripped = angleCode.strip();
        return stripped.isEmpty() ? null : stripped;
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
