package com.ssafy.a307.accident.entity;

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
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

/**
 * 이미지 변형본 하나의 저장 위치와 메타. {@code uk_aia UNIQUE (image_id, variant)} 로
 * <b>variant 는 이미지당 1개</b>다.
 * <p>
 * {@code width} · {@code height} · {@code file_size} 가 nullable 인 것은 정본의 설계다 —
 * 전처리로 메타를 얻지 못해도 행은 남을 수 있다. 그래서 값이 없으면 null 을 넣고
 * 0 이나 추정치로 채우지 않는다.
 * <p>
 * <b>크기 단위 주의</b> — {@code width}/{@code height} 는 SMALLINT 라 32,767 을 넘을 수 없다.
 * 그보다 큰 변은 저장하지 않고 null 로 남긴다. 담을 수 없는 값을 잘라 넣으면 잘못된 해상도가
 * 분석 파이프라인으로 흘러간다.
 */
@Entity
@Table(name = "accident_image_asset")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccidentImageAsset {

    /** {@code ck_aia_size} — 정본이 강제하는 장당 상한. 요구사항 21행의 20MB 와 같은 값이다. */
    public static final int MAX_FILE_SIZE_BYTES = 20_971_520;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "asset_id")
    private Long assetId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "image_id", nullable = false, updatable = false)
    private AccidentImage image;

    @Enumerated(EnumType.STRING)
    @Column(name = "variant", nullable = false, length = 20, updatable = false)
    private ImageVariant variant;

    @Column(name = "s3_key", nullable = false, length = 500)
    private String s3Key;

    @Column(name = "width")
    private Short width;

    @Column(name = "height")
    private Short height;

    @Column(name = "file_size")
    private Integer fileSize;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private AccidentImageAsset(
            ImageVariant variant, String s3Key, Short width, Short height, Integer fileSize) {
        this.variant = variant;
        this.s3Key = s3Key;
        this.width = width;
        this.height = height;
        this.fileSize = fileSize;
    }

    public static AccidentImageAsset of(
            ImageVariant variant, String s3Key, int width, int height, long fileSize) {
        if (variant == null) throw new IllegalArgumentException("variant is required");
        if (s3Key == null || s3Key.isBlank()) throw new IllegalArgumentException("s3Key is required");
        if (s3Key.length() > 500) throw new IllegalArgumentException("s3Key must not exceed 500 characters");
        if (fileSize > MAX_FILE_SIZE_BYTES) {
            throw new IllegalArgumentException("file_size must not exceed " + MAX_FILE_SIZE_BYTES);
        }
        return new AccidentImageAsset(
                variant, s3Key, dimension(width), dimension(height),
                fileSize > 0 ? (int) fileSize : null);
    }

    void attachTo(AccidentImage image) {
        this.image = image;
    }

    /** SMALLINT 에 담을 수 없는 변은 null. 잘라 넣지 않는다. */
    private static Short dimension(int value) {
        return value > 0 && value <= Short.MAX_VALUE ? (short) value : null;
    }
}
