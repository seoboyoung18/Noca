package com.ssafy.a307.accident.image;

import com.ssafy.a307.accident.entity.AccidentImageAsset;
import com.ssafy.a307.accident.entity.ImageVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("사고 이미지 S3 키 규칙")
class AccidentImageKeysTest {

    @Test
    @DisplayName("accidents/{accidentId}/images/{imageId}/{variant}.{ext} 형태다")
    void keyShape() {
        assertThat(AccidentImageKeys.key(12, 340, ImageVariant.ORIGINAL, "jpg"))
                .isEqualTo("accidents/12/images/340/original.jpg");
        assertThat(AccidentImageKeys.key(12, 340, ImageVariant.RESIZED, "jpg"))
                .isEqualTo("accidents/12/images/340/resized.jpg");
        assertThat(AccidentImageKeys.key(12, 340, ImageVariant.THUMBNAIL, "jpg"))
                .isEqualTo("accidents/12/images/340/thumbnail.jpg");
    }

    @Test
    @DisplayName("variant 는 키에서 소문자, DB 에는 대문자 상수명이다")
    void variantCase() {
        assertThat(ImageVariant.BLURRED.objectName()).isEqualTo("blurred");
        assertThat(ImageVariant.BLURRED.name()).isEqualTo("BLURRED");
    }

    @Test
    @DisplayName("접두어로 사고·이미지 단위 정리가 가능하다")
    void prefixes() {
        assertThat(AccidentImageKeys.accidentPrefix(12)).isEqualTo("accidents/12/images/");
        assertThat(AccidentImageKeys.imagePrefix(12, 340)).isEqualTo("accidents/12/images/340/");
        assertThat(AccidentImageKeys.key(12, 340, ImageVariant.ORIGINAL, "jpg"))
                .startsWith(AccidentImageKeys.imagePrefix(12, 340));
    }

    @Test
    @DisplayName("확장자에 경로 구분자나 점이 들어오면 거절한다")
    void rejectsPathTraversalInExtension() {
        assertThatThrownBy(() -> AccidentImageKeys.key(1, 1, ImageVariant.ORIGINAL, "../jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccidentImageKeys.key(1, 1, ImageVariant.ORIGINAL, "jpg/x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccidentImageKeys.key(1, 1, ImageVariant.ORIGINAL, ".jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ID 와 확장자가 없거나 잘못되면 거절한다")
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> AccidentImageKeys.key(0, 1, ImageVariant.ORIGINAL, "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccidentImageKeys.key(1, 0, ImageVariant.ORIGINAL, "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccidentImageKeys.key(1, 1, null, "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccidentImageKeys.key(1, 1, ImageVariant.ORIGINAL, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("가장 큰 ID 조합에서도 s3_key VARCHAR(500) 에 들어간다")
    void fitsColumn() {
        String key = AccidentImageKeys.key(
                Long.MAX_VALUE, Long.MAX_VALUE, ImageVariant.THUMBNAIL, "jpeg");

        assertThat(key.length()).isLessThanOrEqualTo(AccidentImageKeys.MAX_KEY_LENGTH);
        assertThat(AccidentImageAsset.of(ImageVariant.THUMBNAIL, key, 1, 1, 1).getS3Key())
                .isEqualTo(key);
    }

    @Test
    @DisplayName("원본 파일명을 키에 넣지 않는다")
    void doesNotContainOriginalFilename() {
        String key = AccidentImageKeys.key(12, 340, ImageVariant.ORIGINAL, "jpg");

        assertThat(key).doesNotContain("..").doesNotContain(" ");
        assertThat(key.chars().filter(c -> c == '/').count()).isEqualTo(4);
    }
}
