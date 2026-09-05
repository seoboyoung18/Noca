package com.ssafy.a307.guide.service;

import com.ssafy.a307.guide.dto.ChecklistResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code resources/checklist.json} 의 실제 문안을 검증한다.
 * 기획이 JSON 을 고치다 깨뜨렸을 때 배포가 아니라 빌드에서 잡히게 하는 것이 목적이다.
 */
@DisplayName("체크리스트 문안 파일")
class ChecklistContentTest {

    private final GuideContentLoader loader = new GuideContentLoader(JsonMapper.builder().build());

    private ChecklistResponse checklist() {
        return loader.load("checklist.json", ChecklistResponse.class);
    }

    @Test
    @DisplayName("5단계 12항목이고 order 가 1..5 로 이어진다")
    void checklistShape() {
        ChecklistResponse checklist = checklist();

        assertThat(checklist.steps()).extracting(step -> step.order())
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(checklist.steps()).extracting(step -> step.title())
                .containsExactly("안전 확보", "상대 차량 확인", "현장 촬영", "블랙박스", "보험사 접수");

        // 와이어프레임의 진행률 표기가 "4 / 12" 다. 항목 총합이 12 여야 화면과 맞는다.
        int totalItems = checklist.steps().stream().mapToInt(step -> step.items().size()).sum();
        assertThat(totalItems).isEqualTo(12);
    }

    @Test
    @DisplayName("법적 수치·과실 조언·의료 판단이 문안에 들어오면 실패한다")
    void checklistStaysOutOfForbiddenTerritory() {
        List<String> items = checklist().steps().stream()
                .flatMap(step -> step.items().stream())
                .toList();

        assertThat(items).isNotEmpty();
        // 잘못된 사고 대응 안내는 실제 피해로 이어진다. 회귀를 빌드에서 막는다.
        assertThat(items).allSatisfy(item -> assertThat(item)
                .doesNotContain("미터", "합의", "과실", "책임", "병원", "치료", "부상"));
        // 수치 규정(예: "후방 100m")이 끼어들지 않았는지 — 문안에 숫자가 없어야 한다.
        assertThat(items).allSatisfy(item -> assertThat(item).doesNotMatch(".*\\d.*"));
    }

    @Test
    @DisplayName("문안 파일이 없으면 조용히 넘어가지 않고 예외를 던진다")
    void missingContentFailsLoudly() {
        assertThatThrownBy(() -> loader.load("guide-that-does-not-exist.json", Map.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("guide-that-does-not-exist.json");
    }
}
