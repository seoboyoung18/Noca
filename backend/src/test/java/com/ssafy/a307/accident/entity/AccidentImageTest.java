package com.ssafy.a307.accident.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("사고 이미지 엔티티")
class AccidentImageTest {

    @Test
    @DisplayName("예약된 이미지는 PASS 로 시작하고 완료 상태가 아니다")
    void reserved() {
        AccidentImage image = AccidentImage.reserve(null, "front.jpg");

        assertThat(image.getQualityStatus()).isEqualTo(ImageQualityStatus.PASS);
        assertThat(image.getQualityReason()).isNull();
        assertThat(image.isUploadCompleted()).isFalse();
        assertThat(image.getAssets()).isEmpty();
    }

    @Test
    @DisplayName("ORIGINAL asset 이 붙으면 업로드 완료로 본다")
    void uploadCompletedByOriginalAsset() {
        AccidentImage image = AccidentImage.reserve(null, "front.jpg");

        image.addAsset(AccidentImageAsset.of(ImageVariant.THUMBNAIL, "k/thumbnail.jpg", 10, 10, 10));
        assertThat(image.isUploadCompleted()).isFalse();

        image.addAsset(AccidentImageAsset.of(ImageVariant.ORIGINAL, "k/original.jpg", 10, 10, 10));
        assertThat(image.isUploadCompleted()).isTrue();
        assertThat(image.hasVariant(ImageVariant.RESIZED)).isFalse();
        assertThat(image.asset(ImageVariant.ORIGINAL)).isPresent();
    }

    @Test
    @DisplayName("asset 컬렉션은 밖에서 고칠 수 없다")
    void assetsAreUnmodifiable() {
        AccidentImage image = AccidentImage.reserve(null, "front.jpg");

        assertThatThrownBy(() -> image.getAssets().add(
                AccidentImageAsset.of(ImageVariant.ORIGINAL, "k", 1, 1, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("WARN 사유는 quality_reason 의 100자로 잘린다")
    void warnReasonIsTruncated() {
        AccidentImage image = AccidentImage.reserve(null, "front.jpg");

        image.markQuality(ImageQualityStatus.WARN, "가".repeat(150));

        assertThat(image.getQualityReason()).hasSize(AccidentImage.MAX_QUALITY_REASON_LENGTH);
    }

    @Test
    @DisplayName("PASS 로 되돌리면 사유를 지운다 — 통과한 이미지에 사유가 남으면 화면이 잘못 그린다")
    void passClearsReason() {
        AccidentImage image = AccidentImage.reserve(null, "front.jpg");
        image.markQuality(ImageQualityStatus.WARN, "해상도 부족");

        image.markQuality(ImageQualityStatus.PASS, "해상도 부족");

        assertThat(image.getQualityStatus()).isEqualTo(ImageQualityStatus.PASS);
        assertThat(image.getQualityReason()).isNull();
    }

    @Test
    @DisplayName("SMALLINT 에 담을 수 없는 변은 null 로 남긴다 — 잘라 넣지 않는다")
    void oversizedDimensionsBecomeNull() {
        AccidentImageAsset asset = AccidentImageAsset.of(
                ImageVariant.ORIGINAL, "k/original.jpg", Short.MAX_VALUE + 1, 100, 100);

        assertThat(asset.getWidth()).isNull();
        assertThat(asset.getHeight()).isEqualTo((short) 100);
    }

    @Test
    @DisplayName("ck_aia_size 를 넘는 크기는 엔티티에서 먼저 거절한다")
    void rejectsOversizedFile() {
        assertThatThrownBy(() -> AccidentImageAsset.of(
                ImageVariant.ORIGINAL, "k/original.jpg", 100, 100,
                AccidentImageAsset.MAX_FILE_SIZE_BYTES + 1L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(AccidentImageAsset.of(
                ImageVariant.ORIGINAL, "k/original.jpg", 100, 100,
                AccidentImageAsset.MAX_FILE_SIZE_BYTES).getFileSize())
                .isEqualTo(AccidentImageAsset.MAX_FILE_SIZE_BYTES);
    }

    @Test
    @DisplayName("s3Key 가 없거나 500자를 넘으면 거절한다")
    void rejectsInvalidKey() {
        assertThatThrownBy(() -> AccidentImageAsset.of(ImageVariant.ORIGINAL, " ", 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccidentImageAsset.of(
                ImageVariant.ORIGINAL, "k".repeat(501), 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
