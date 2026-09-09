package com.ssafy.a307.estimatevalidation.file.llm;

import com.ssafy.a307.estimatevalidation.file.SummaryGenerationPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요약 지시문. <b>LLM 을 부르지 않는다</b> — 만들어진 지시문 문자열만 본다.
 *
 * <p>여기서 지키려는 성질은 하나다. <b>모델에게 판단을 맡기지 않는다.</b> 등급·총액·항목 수는
 * 규칙 엔진이 이미 정한 값이고, 지시문은 그 값을 문장으로 옮기라고만 시켜야 한다.
 */
@DisplayName("요약 지시문")
class SummaryPromptFactoryTest {

    @Test
    @DisplayName("이미 계산된 값을 지시문에 그대로 싣는다")
    void carriesComputedValues() {
        String instruction = SummaryPromptFactory.instruction(
                new SummaryGenerationPort.SummaryRequest(7L, "CAUTION", 1_250_000, List.of(
                        new SummaryGenerationPort.SummaryIssue(
                                1, "프론트 펜더", "OVER_P75", "소계가 유사 사례 75백분위를 초과합니다."))),
                "주의");

        assertThat(instruction)
                .contains("주의")
                .contains("1,250,000원")
                .contains("1개")
                .contains("1행 프론트 펜더")
                .contains("소계가 유사 사례 75백분위를 초과합니다.");
    }

    @Test
    @DisplayName("새로 계산하지 말라고 못 박는다")
    void forbidsInventingValues() {
        String instruction = SummaryPromptFactory.instruction(
                new SummaryGenerationPort.SummaryRequest(1L, "APPROPRIATE", 500_000, List.of()), "적정");

        assertThat(instruction)
                .contains("위에 적힌 값만 사용하세요")
                .contains("새로 계산하거나 바꾸지 마세요")
                .contains("추측하지 마세요");
    }

    @Test
    @DisplayName("특정 사업자를 평가하지 말라고 명시한다 — LEGAL_NOTICE 의 취지다")
    void forbidsJudgingBusinesses() {
        String instruction = SummaryPromptFactory.instruction(
                new SummaryGenerationPort.SummaryRequest(1L, "NEEDS_REVIEW", 900_000, List.of()), "검토 필요");

        assertThat(instruction)
                .contains("평가하거나 비난하는 표현을 쓰지 마세요")
                .contains("사업자를 평가하지 않습니다")
                .contains("바가지");
    }

    @Test
    @DisplayName("확인 권장 항목이 없으면 없다고 적는다 — 비워 두지 않는다")
    void statesEmptyIssuesExplicitly() {
        String instruction = SummaryPromptFactory.instruction(
                new SummaryGenerationPort.SummaryRequest(1L, "APPROPRIATE", 300_000, List.of()), "적정");

        assertThat(instruction).contains("- 없음").contains("0개");
    }

    @Test
    @DisplayName("항목이 많으면 앞부분만 싣고 나머지는 건수로 줄인다")
    void capsIssueList() {
        List<SummaryGenerationPort.SummaryIssue> issues = IntStream.rangeClosed(1, 25)
                .mapToObj(i -> new SummaryGenerationPort.SummaryIssue(
                        i, "항목" + i, "OVER_P75", "사유" + i))
                .toList();

        String instruction = SummaryPromptFactory.instruction(
                new SummaryGenerationPort.SummaryRequest(1L, "NEEDS_REVIEW", 5_000_000, issues), "검토 필요");

        assertThat(instruction).contains("항목20").doesNotContain("항목21");
        assertThat(instruction).contains("(외 5건)");
        // 실제 건수는 줄이지 않는다 — 25개를 확인 권장했다는 사실은 그대로 전한다.
        assertThat(instruction).contains("25개");
    }
}
