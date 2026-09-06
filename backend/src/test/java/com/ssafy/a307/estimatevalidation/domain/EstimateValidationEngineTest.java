package com.ssafy.a307.estimatevalidation.domain;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EstimateValidationEngineTest {

    private final EstimateValidationEngine engine = new EstimateValidationEngine();

    @Test
    void overP75IsStrictAndNotInclusive() {
        List<ValidatedLine> result = engine.validate(new ValidationInput(
                List.of(
                        line(1, "P1", WorkType.REPAIR, 74),
                        line(2, "P2", WorkType.REPAIR, 75),
                        line(3, "P3", WorkType.REPAIR, 76)),
                Map.of(
                        1, new CostReference(75, 10),
                        2, new CostReference(75, 10),
                        3, new CostReference(75, 10)),
                AnalysisSnapshot.absent()));

        assertThat(result.get(0).flags()).doesNotContain(ValidationFlag.OVER_P75);
        assertThat(result.get(1).flags()).doesNotContain(ValidationFlag.OVER_P75);
        assertThat(result.get(2).flags()).contains(ValidationFlag.OVER_P75);
    }

    @Test
    void missingAnalysisNeverProducesNotInAnalysis() {
        ValidatedLine result = engine.validate(new ValidationInput(
                List.of(line(1, "P1", WorkType.REPLACEMENT, 100)),
                Map.of(1, new CostReference(100, 5)),
                AnalysisSnapshot.absent())).getFirst();

        assertThat(result.flags()).doesNotContain(ValidationFlag.NOT_IN_ANALYSIS);
    }

    @Test
    void presentAnalysisOnlyFlagsMissingMappedReplacement() {
        List<ValidatedLine> result = engine.validate(new ValidationInput(
                List.of(
                        line(1, "P1", WorkType.REPLACEMENT, 100),
                        line(2, "P2", WorkType.REPLACEMENT, 100),
                        line(3, "P3", WorkType.REPAIR, 100),
                        line(4, null, WorkType.REPLACEMENT, 100)),
                Map.of(
                        1, new CostReference(100, 5), 2, new CostReference(100, 5),
                        3, new CostReference(100, 5)),
                new AnalysisSnapshot(true, Set.of("P1"))));

        assertThat(result.get(0).flags()).doesNotContain(ValidationFlag.NOT_IN_ANALYSIS);
        assertThat(result.get(1).flags()).contains(ValidationFlag.NOT_IN_ANALYSIS);
        assertThat(result.get(2).flags()).doesNotContain(ValidationFlag.NOT_IN_ANALYSIS);
        assertThat(result.get(3).flags()).contains(ValidationFlag.UNMAPPED_ITEM)
                .doesNotContain(ValidationFlag.NOT_IN_ANALYSIS);
    }

    @Test
    void duplicateLaborFlagsOnlyLaterRowsWithSameMappedPartAndCanonicalWork() {
        List<ValidatedLine> result = engine.validate(new ValidationInput(
                List.of(
                        line(1, "P1", WorkType.REPAIR, 100),
                        line(2, "P1", WorkType.REPAIR, 200),
                        line(3, "P2", WorkType.REPAIR, 200),
                        lineWithCosts(4, "P1", WorkType.REPAIR, 50, 0),
                        line(5, null, WorkType.REPAIR, 200)),
                Map.of(
                        1, new CostReference(1_000, 5), 2, new CostReference(1_000, 5),
                        3, new CostReference(1_000, 5), 4, new CostReference(1_000, 5)),
                AnalysisSnapshot.absent()));

        assertThat(result.get(0).flags()).doesNotContain(ValidationFlag.DUPLICATE_LABOR);
        assertThat(result.get(1).flags()).contains(ValidationFlag.DUPLICATE_LABOR);
        assertThat(result.get(2).flags()).doesNotContain(ValidationFlag.DUPLICATE_LABOR);
        assertThat(result.get(3).flags()).doesNotContain(ValidationFlag.DUPLICATE_LABOR);
        assertThat(result.get(4).flags()).doesNotContain(ValidationFlag.DUPLICATE_LABOR);
    }

    @Test
    void unmappedAndInsufficientReferenceAreNotOvercharge() {
        List<ValidatedLine> result = engine.validate(new ValidationInput(
                List.of(
                        line(1, null, WorkType.REPAIR, 10_000),
                        line(2, "P2", WorkType.REPAIR, 10_000),
                        line(3, "P3", WorkType.DETACHMENT, 10_000)),
                Map.of(), AnalysisSnapshot.absent()));

        assertThat(result.get(0).flags()).containsExactly(ValidationFlag.UNMAPPED_ITEM);
        assertThat(result.get(1).flags()).containsExactly(ValidationFlag.INSUFFICIENT_REFERENCE);
        assertThat(result.get(2).flags()).containsExactly(ValidationFlag.INSUFFICIENT_REFERENCE);
    }

    @Test
    void subtotalUsesExactArithmetic() {
        EstimateLine line = lineWithCosts(1, "P1", WorkType.REPAIR, Long.MAX_VALUE, 1);
        assertThatThrownBy(line::subtotal).isInstanceOf(ArithmeticException.class);
    }

    private static EstimateLine line(int lineNo, String partCode, WorkType workType, long laborCost) {
        return lineWithCosts(lineNo, partCode, workType, 0, laborCost);
    }

    private static EstimateLine lineWithCosts(
            int lineNo, String partCode, WorkType workType, long partCost, long laborCost) {
        return new EstimateLine(lineNo, "원문 " + lineNo, partCode, "표준 부품 " + lineNo,
                workType, 1, partCost, laborCost);
    }
}
