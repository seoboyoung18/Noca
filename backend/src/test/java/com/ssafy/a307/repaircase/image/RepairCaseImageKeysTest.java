package com.ssafy.a307.repaircase.image;

import com.ssafy.a307.accident.entity.ImageVariant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AI-Hub 검색 사례 이미지 S3 key 규칙")
class RepairCaseImageKeysTest {

    @Test
    @DisplayName("repair-cases/{caseId}/images/{caseImageId}/{variant}.{ext} 형태다")
    void keyShape() {
        assertThat(RepairCaseImageKeys.key(1205, 88421, ImageVariant.ORIGINAL, "jpg"))
                .isEqualTo("repair-cases/1205/images/88421/original.jpg");
        assertThat(RepairCaseImageKeys.key(1205, 88421, ImageVariant.THUMBNAIL, "jpg"))
                .isEqualTo("repair-cases/1205/images/88421/thumbnail.jpg");
    }

    @Test
    @DisplayName("사례·이미지 단위 접두어를 만든다")
    void prefixes() {
        assertThat(RepairCaseImageKeys.casePrefix(1205))
                .isEqualTo("repair-cases/1205/images/");
        assertThat(RepairCaseImageKeys.imagePrefix(1205, 88421))
                .isEqualTo("repair-cases/1205/images/88421/");
    }

    @Test
    @DisplayName("ID·variant·확장자가 없거나 확장자에 경로가 들어오면 거절한다")
    void rejectsInvalidArguments() {
        assertThatThrownBy(() -> RepairCaseImageKeys.key(0, 1, ImageVariant.ORIGINAL, "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepairCaseImageKeys.key(1, 0, ImageVariant.ORIGINAL, "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepairCaseImageKeys.key(1, 1, null, "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RepairCaseImageKeys.key(1, 1, ImageVariant.ORIGINAL, "../jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
