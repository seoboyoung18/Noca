package com.ssafy.a307.estimatevalidation.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RepairShopQuestionGeneratorTest {

    private final RepairShopQuestionGenerator generator = new RepairShopQuestionGenerator();

    @Test
    void createsNoQuestionWithoutFlag() {
        assertThat(generator.generate(List.of(source(1, "펜더", Set.of())))).isEmpty();
    }

    @Test
    void ordersByLineThenFixedFlagPriorityAndAssignsStableDisplayOrder() {
        List<GeneratedQuestion> result = generator.generate(List.of(
                source(2, "도어", Set.of(ValidationFlag.UNMAPPED_ITEM)),
                source(1, "펜더", Set.of(ValidationFlag.DUPLICATE_LABOR, ValidationFlag.OVER_P75))));

        assertThat(result).extracting(GeneratedQuestion::lineNo).containsExactly(1, 1, 2);
        assertThat(result).extracting(GeneratedQuestion::sourceFlag).containsExactly(
                ValidationFlag.OVER_P75, ValidationFlag.DUPLICATE_LABOR, ValidationFlag.UNMAPPED_ITEM);
        assertThat(result).extracting(GeneratedQuestion::displayOrder).containsExactly(1, 2, 3);
    }

    @Test
    void deduplicatesSameMeaningQuestion() {
        List<GeneratedQuestion> result = generator.generate(List.of(
                source(1, "펜더", Set.of(ValidationFlag.OVER_P75)),
                source(1, "펜더", Set.of(ValidationFlag.OVER_P75))));

        assertThat(result).hasSize(1);
    }

    @Test
    void prefersStandardNameAndSanitizesUnsafeFallback() {
        QuestionSource standard = new QuestionSource(1, "<script>alert(1)</script>\n사기", "프론트 펜더",
                WorkType.SHEET_METAL, Set.of(ValidationFlag.OVER_P75));
        QuestionSource fallback = new QuestionSource(2, "<script>alert(1)</script>\u0000허위", null,
                WorkType.REPAIR, Set.of(ValidationFlag.UNMAPPED_ITEM));

        List<GeneratedQuestion> result = generator.generate(List.of(standard, fallback));

        assertThat(result.get(0).text()).startsWith("프론트 펜더 판금 비용")
                .doesNotContain("script", "alert", "사기", "허위", "\n");
        assertThat(result.get(1).text()).doesNotContain("<", ">", "\u0000", "script", "alert", "허위");
        assertThat(result).allSatisfy(question -> {
            assertThat(question.text()).endsWith("?");
            assertThat(question.text().length()).isLessThanOrEqualTo(500);
        });
    }

    @Test
    void templatesRemainNeutralAndCompleteForEveryFlag() {
        List<GeneratedQuestion> result = generator.generate(List.of(source(1, "펜더", Set.of(ValidationFlag.values()))));

        assertThat(result).hasSize(ValidationFlag.values().length);
        assertThat(result).allSatisfy(question -> assertThat(question.text())
                .endsWith("?")
                .doesNotContain("사기", "허위", "불필요한 수리"));
    }

    private static QuestionSource source(int lineNo, String name, Set<ValidationFlag> flags) {
        return new QuestionSource(lineNo, name, name, WorkType.REPAIR, flags);
    }
}
