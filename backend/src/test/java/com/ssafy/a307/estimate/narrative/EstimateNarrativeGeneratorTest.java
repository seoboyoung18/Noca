package com.ssafy.a307.estimate.narrative;

import com.ssafy.a307.common.llm.LlmChatPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LLM 응답을 어떻게 읽는가 (S15P21A307-537).
 *
 * <p><b>Spring 을 띄우지 않는다.</b> 여기서 보는 것은 해석 규칙뿐이고 DB 도 트랜잭션도 쓰지 않는다.
 * 저장까지 이어지는 흐름은 {@link EstimateNarrativeWorkerTest} 가 본다.
 *
 * <p>핵심은 <b>지어낸 숫자를 들이지 않는다</b> 이다. 근거 문장은 규칙이 만든 원문을 다듬는 것이라
 * 원문에 없던 숫자가 섞이면 그 문장을 버린다 — 버려진 자리에는 규칙 문장이 그대로 남는다.
 */
@DisplayName("견적 요약 응답 해석 (S15P21A307-537)")
class EstimateNarrativeGeneratorTest {

    private static final String BASIS =
            "교환 · 참조 사례 12건의 중앙값 기준으로 산정했습니다.";

    /** 근거가 있는 부위 하나와 없는 부위 하나. 지시문에 적히는 것도 이 둘뿐이다. */
    private static final EstimateNarrativeContext CONTEXT = new EstimateNarrativeContext(
            "현대", "아반떼", (short) 2020,
            true, null, "HIGH", 700_000, 800_000, 900_000, 12,
            List.of(new EstimateNarrativeContext.ItemView(
                            "FRONT_BUMPER", "앞 범퍼", "교환", 800_000, 12, BASIS),
                    new EstimateNarrativeContext.ItemView(
                            "HEAD_LAMP_L", "헤드램프(좌)", "교환", 300_000, 0, null)),
            List.of());

    private final ScriptedPort port = new ScriptedPort();
    private final EstimateNarrativeGenerator generator =
            new EstimateNarrativeGenerator(Optional.of(port), new ObjectMapper());

    /** 테스트가 정해 준 JSON 을 그대로 돌려주는 대역. */
    private static final class ScriptedPort implements LlmChatPort {

        private String json;

        @Override
        public ChatResult complete(ChatRequest request) {
            return new ChatResult(json, "test-model", Usage.unknown());
        }
    }

    /**
     * <b>저장되는 JSON 은 계약이다.</b> 이 record 는 {@code estimate_narrative.content} 에 그대로
     * 들어가는데, Jackson 은 {@code isEmpty()} 같은 헬퍼를 빈 게터로 보고 함께 직렬화한다 —
     * 실제로 운영에 {@code "empty": false} 가 11건 들어갔다. 다시 새어 나가면 여기서 깨진다.
     */
    @Test
    @DisplayName("저장되는 JSON 에는 문장 필드 셋만 들어간다")
    void serializesOnlyContentFields() {
        String json = new ObjectMapper().writeValueAsString(
                new EstimateNarrativeContent("요약", List.of("확인 권장"), List.of()));

        assertThat(json)
                .contains("\"summary\"")
                .contains("\"cautions\"")
                .contains("\"basisNotes\"")
                .doesNotContain("\"empty\"");
    }

    @Test
    @DisplayName("요약·확인 권장·근거 문장을 읽는다")
    void readsSummaryCautionsAndNotes() {
        port.json = """
                {
                  "summary": "앞 범퍼 교환이 중심인 사고입니다.",
                  "cautions": ["부품 등급을 확인하세요."],
                  "basisNotes": [
                    {"partCode":"FRONT_BUMPER","text":"비슷한 사례 12건의 가운데 값으로 계산했습니다."}
                  ]
                }
                """;

        EstimateNarrativeContent content = generator.generate(CONTEXT);

        assertThat(content.summary()).isEqualTo("앞 범퍼 교환이 중심인 사고입니다.");
        assertThat(content.cautions()).containsExactly("부품 등급을 확인하세요.");
        assertThat(content.noteFor("FRONT_BUMPER"))
                .isEqualTo("비슷한 사례 12건의 가운데 값으로 계산했습니다.");
    }

    /**
     * 이 테스트가 이 기능의 안전선이다. 원문은 <b>12건</b>인데 답이 <b>30건</b>이라고 하면 그
     * 문장은 버린다 — 사용자가 보험사에 들고 가는 근거가 되기 때문이다.
     */
    @Test
    @DisplayName("원문에 없던 숫자가 섞이면 그 근거 문장을 버린다")
    void dropsNoteThatInventsNumbers() {
        port.json = """
                {
                  "summary": "요약",
                  "cautions": [],
                  "basisNotes": [
                    {"partCode":"FRONT_BUMPER","text":"비슷한 사례 30건의 가운데 값으로 계산했습니다."}
                  ]
                }
                """;

        assertThat(generator.generate(CONTEXT).noteFor("FRONT_BUMPER")).isNull();
    }

    /** 숫자를 <b>지우는</b> 것은 막지 않는다. 없던 숫자를 더하는 것만 막는다. */
    @Test
    @DisplayName("숫자를 덜어낸 문장은 그대로 쓴다")
    void keepsNoteThatRemovesNumbers() {
        port.json = """
                {
                  "summary": "요약",
                  "cautions": [],
                  "basisNotes": [
                    {"partCode":"FRONT_BUMPER","text":"비슷한 수리 사례의 가운데 값으로 계산했습니다."}
                  ]
                }
                """;

        assertThat(generator.generate(CONTEXT).noteFor("FRONT_BUMPER"))
                .isEqualTo("비슷한 수리 사례의 가운데 값으로 계산했습니다.");
    }

    /**
     * 근거가 없는 항목에는 다듬을 원문이 없다. 그 자리에 문장이 생겼다면 LLM 이 지어낸 것이다 —
     * 화면은 그 항목을 "근거 없음" 으로 표시해야 한다.
     */
    @Test
    @DisplayName("근거가 없는 부위의 문장은 버린다")
    void dropsNoteForItemWithoutBasis() {
        port.json = """
                {
                  "summary": "요약",
                  "cautions": [],
                  "basisNotes": [
                    {"partCode":"HEAD_LAMP_L","text":"헤드램프는 유사 사례가 충분합니다."}
                  ]
                }
                """;

        assertThat(generator.generate(CONTEXT).basisNotes()).isEmpty();
    }

    @Test
    @DisplayName("지시문에 없던 부품 코드는 버린다")
    void dropsUnknownPartCode() {
        port.json = """
                {
                  "summary": "요약",
                  "cautions": [],
                  "basisNotes": [{"partCode":"TRUNK_LID","text":"트렁크도 확인했습니다."}]
                }
                """;

        assertThat(generator.generate(CONTEXT).basisNotes()).isEmpty();
    }

    @Test
    @DisplayName("확인 권장은 세 줄까지만 남긴다")
    void capsCautions() {
        port.json = """
                {
                  "summary": "요약",
                  "cautions": ["하나", "둘", "셋", "넷", "다섯"],
                  "basisNotes": []
                }
                """;

        assertThat(generator.generate(CONTEXT).cautions()).containsExactly("하나", "둘", "셋");
    }

    @Test
    @DisplayName("쓸 문장이 하나도 없으면 실패다 — 빈 요약을 성공으로 남기지 않는다")
    void emptyResponseIsFailure() {
        port.json = """
                {"summary": "", "cautions": [], "basisNotes": []}
                """;

        assertThatThrownBy(() -> generator.generate(CONTEXT))
                .isInstanceOf(EstimateNarrativeGenerationException.class)
                .extracting(e -> ((EstimateNarrativeGenerationException) e).getFailure())
                .isEqualTo(EstimateNarrativeFailure.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("JSON 이 깨져 있으면 실패다")
    void brokenJsonIsFailure() {
        port.json = "{\"summary\": ";

        assertThatThrownBy(() -> generator.generate(CONTEXT))
                .isInstanceOf(EstimateNarrativeGenerationException.class)
                .extracting(e -> ((EstimateNarrativeGenerationException) e).getFailure())
                .isEqualTo(EstimateNarrativeFailure.INVALID_RESPONSE);
    }

    /** 키가 없는 환경이다. 접수는 이미 돼 있으므로 워커가 사유를 적고 끝낸다. */
    @Test
    @DisplayName("LLM 어댑터가 없으면 LLM_UNAVAILABLE 이다")
    void missingAdapterIsUnavailable() {
        EstimateNarrativeGenerator noAdapter =
                new EstimateNarrativeGenerator(Optional.empty(), new ObjectMapper());

        assertThatThrownBy(() -> noAdapter.generate(CONTEXT))
                .isInstanceOf(EstimateNarrativeGenerationException.class)
                .extracting(e -> ((EstimateNarrativeGenerationException) e).getFailure())
                .isEqualTo(EstimateNarrativeFailure.LLM_UNAVAILABLE);
    }

    /** 산정 불가 견적도 대상이다 — 금액이 없을수록 설명할 문장이 필요하다. */
    @Test
    @DisplayName("산정 불가 견적에도 요약을 만든다")
    void worksForNonEstimable() {
        EstimateNarrativeContext nonEstimable = new EstimateNarrativeContext(
                "현대", "아반떼", (short) 2020, false, "INSUFFICIENT_CASES", null,
                null, null, null, null, List.of(), List.of());
        port.json = """
                {
                  "summary": "비슷한 수리 사례가 부족해 금액을 내지 못했습니다.",
                  "cautions": ["정비소 견적서를 받아 비교해 보세요."],
                  "basisNotes": []
                }
                """;

        EstimateNarrativeContent content = generator.generate(nonEstimable);

        assertThat(content.summary()).contains("금액을 내지 못했습니다");
        assertThat(content.cautions()).hasSize(1);
    }
}
