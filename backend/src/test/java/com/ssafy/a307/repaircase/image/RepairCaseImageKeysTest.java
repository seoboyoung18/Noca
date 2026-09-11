package com.ssafy.a307.repaircase.image;

import com.ssafy.a307.accident.entity.ImageVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AI-Hub 검색 사례 이미지 S3 key 규칙")
class RepairCaseImageKeysTest {

    @Test
    @DisplayName("repair-cases/{source}/{externalRef}/{sourceImageId}/{variant}.{ext} 형태다")
    void keyShape() {
        assertThat(RepairCaseImageKeys.key("AIHUB_AS", "as-0000160", "0406472",
                ImageVariant.ORIGINAL, "jpg"))
                .isEqualTo("repair-cases/AIHUB_AS/as-0000160/0406472/original.jpg");
        assertThat(RepairCaseImageKeys.key("AIHUB_AS", "as-0000160", "0406472",
                ImageVariant.THUMBNAIL, "jpg"))
                .isEqualTo("repair-cases/AIHUB_AS/as-0000160/0406472/thumbnail.jpg");
    }

    @Test
    @DisplayName("원천 사례·이미지 접두어를 만든다")
    void prefixes() {
        assertThat(RepairCaseImageKeys.casePrefix("AIHUB_AS", "as-0000160"))
                .isEqualTo("repair-cases/AIHUB_AS/as-0000160/");
        assertThat(RepairCaseImageKeys.imagePrefix("AIHUB_AS", "as-0000160", "0406472"))
                .isEqualTo("repair-cases/AIHUB_AS/as-0000160/0406472/");
    }

    @Test
    @DisplayName("경로 segment·variant·확장자가 없거나 위험하면 거절한다")
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> RepairCaseImageKeys.key("", "as-0000160", "0406472",
                ImageVariant.ORIGINAL, "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepairCaseImageKeys.key("AIHUB_AS", "../as-0000160", "0406472",
                ImageVariant.ORIGINAL, "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepairCaseImageKeys.key("AIHUB_AS", "a/b", "0406472",
                ImageVariant.ORIGINAL, "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepairCaseImageKeys.key("AIHUB_AS", "as-0000160", "../0406472",
                ImageVariant.ORIGINAL, "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepairCaseImageKeys.key("AIHUB_AS", "as-0000160", "0406472",
                null, "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepairCaseImageKeys.key("AIHUB_AS", "as-0000160", "0406472",
                ImageVariant.ORIGINAL, "../jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("sourceImageId 앞자리 0을 보존한다")
    void preservesLeadingZeros() {
        assertThat(RepairCaseImageKeys.key("AIHUB_AS", "as-0000160", "0406472",
                ImageVariant.ORIGINAL, "jpg"))
                .contains("/0406472/original.jpg");
    }

    @Test
    @DisplayName("500자 초과 key를 거절한다")
    void rejectsTooLongKey() {
        String longSegment = "a".repeat(480);
        assertThatThrownBy(() -> RepairCaseImageKeys.key(
                "AIHUB_AS", "as-0000160", longSegment, ImageVariant.ORIGINAL, "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
