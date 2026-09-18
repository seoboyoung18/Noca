package com.ssafy.a307.repairchecklist;

import com.ssafy.a307.common.llm.LlmChatPort;
import com.ssafy.a307.repairchecklist.domain.RepairChecklistDraft;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistItemCategory;
import com.ssafy.a307.repairchecklist.service.RepairChecklistContext;
import com.ssafy.a307.repairchecklist.service.RepairChecklistGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LLM 응답을 어떻게 읽는가 (S15P21A307-544).
 *
 * <p><b>Spring 을 띄우지 않는다.</b> 여기서 보는 것은 파싱 규칙뿐이고, 그것은 DB 도 트랜잭션도
 * 쓰지 않는다. 저장까지 이어지는 흐름은 {@link RepairChecklistGenerationTest} 가 본다.
 *
 * <p>규칙의 핵심은 <b>관대하되 지어내지 않는다</b> 이다 — 분류를 못 읽으면 기본값으로 두어
 * 문장을 살리고, 지시문에 없던 부품 코드는 버린다.
 */
@DisplayName("체크리스트 응답 해석 (S15P21A307-544)")
class RepairChecklistGeneratorParsingTest {

    /** 지시문에 이 두 부위를 적어 준 사고. 모델이 고를 수 있는 코드도 이 둘뿐이다. */
    private static final RepairChecklistContext CONTEXT = new RepairChecklistContext(
            "현대", "아반떼", (short) 2021,
            List.of(new RepairChecklistContext.DamagedPartView("FRONT_BUMPER", "Crushed", "exchange"),
                    new RepairChecklistContext.DamagedPartView("HEAD_LAMP_L", "Breakage", "exchange")));

    private final ScriptedPort port = new ScriptedPort();
    private final RepairChecklistGenerator generator =
            new RepairChecklistGenerator(Optional.of(port), new ObjectMapper());

    /** 테스트가 정해 준 JSON 을 그대로 돌려주는 대역. */
    private static final class ScriptedPort implements LlmChatPort {

        private String json;

        @Override
        public ChatResult complete(ChatRequest request) {
            return new ChatResult(json, "test-model", Usage.unknown());
        }
    }

    @Test
    @DisplayName("분류·부위·이유·요약을 읽는다")
    void readsCategoryPartReasonAndSummary() {
        port.json = """
                {
                  "summary": "전면 충돌 중심의 사고입니다.",
                  "items": [
                    {"content":"범퍼 판금 가능 여부 확인","category":"PART",
                     "partCode":"FRONT_BUMPER","reason":null},
                    {"content":"냉각 부품 충격 여부 확인","category":"HIDDEN",
                     "partCode":null,"reason":"전면 파손 시 뒤쪽까지 충격이 간다"}
                  ]
                }
                """;

        RepairChecklistDraft draft = generator.generate(CONTEXT);

        assertThat(draft.summary()).isEqualTo("전면 충돌 중심의 사고입니다.");
        assertThat(draft.items()).extracting(RepairChecklistDraft.DraftItem::category)
                .containsExactly(RepairChecklistItemCategory.PART, RepairChecklistItemCategory.HIDDEN);
        assertThat(draft.items().getFirst().partCode()).isEqualTo("FRONT_BUMPER");
        assertThat(draft.items().getLast().reason()).isEqualTo("전면 파손 시 뒤쪽까지 충격이 간다");
    }

    /**
     * 부위를 지어내지 못하게 하는 규칙. <b>문장은 살린다</b> — 꼬리표가 틀렸을 뿐이라
     * 줄째로 버리면 쓸 만한 항목까지 사라진다({@code RepairQuestionGenerator} 와 같은 판단).
     */
    @Test
    @DisplayName("지시문에 없던 부품 코드는 비운다 — 항목은 남는다")
    void unknownPartCodeIsCleared() {
        port.json = """
                {"summary":"요약","items":[
                  {"content":"트렁크 단차 확인","category":"PART",
                   "partCode":"TRUNK_LID","reason":null}]}
                """;

        RepairChecklistDraft draft = generator.generate(CONTEXT);

        assertThat(draft.items()).singleElement().satisfies(item -> {
            assertThat(item.content()).isEqualTo("트렁크 단차 확인");
            assertThat(item.partCode()).isNull();
        });
    }

    @Test
    @DisplayName("모르는 분류와 COMMON 은 부품별로 둔다 — 공통 탭은 마스터만 채운다")
    void unknownOrCommonCategoryFallsBackToPart() {
        port.json = """
                {"summary":"요약","items":[
                  {"content":"첫째","category":"COMMON","partCode":null,"reason":null},
                  {"content":"둘째","category":"뭐라고","partCode":null,"reason":null},
                  {"content":"셋째","category":null,"partCode":null,"reason":null}]}
                """;

        assertThat(generator.generate(CONTEXT).items())
                .extracting(RepairChecklistDraft.DraftItem::category)
                .containsOnly(RepairChecklistItemCategory.PART);
    }

    @Test
    @DisplayName("함께 점검은 네 개까지만 남긴다")
    void hiddenItemsAreCapped() {
        StringBuilder json = new StringBuilder("{\"summary\":\"요약\",\"items\":[");
        for (int i = 1; i <= 6; i++) {
            if (i > 1) json.append(',');
            json.append("{\"content\":\"숨은 손상 ").append(i)
                    .append("\",\"category\":\"HIDDEN\",\"partCode\":null,\"reason\":\"이유\"}");
        }
        port.json = json.append("]}").toString();

        assertThat(generator.generate(CONTEXT).items()).hasSize(4);
    }

    @Test
    @DisplayName("PART 항목이 적어 온 이유는 버린다 — 이유 칸은 함께 점검의 것이다")
    void reasonOnPartItemIsDropped() {
        port.json = """
                {"summary":"요약","items":[
                  {"content":"범퍼 확인","category":"PART",
                   "partCode":"FRONT_BUMPER","reason":"굳이 적은 이유"}]}
                """;

        assertThat(generator.generate(CONTEXT).items().getFirst().reason()).isNull();
    }

    /** 요약은 머리글이고 항목이 결과물이다. 한 문장 때문에 호출 비용 전체를 버리지 않는다. */
    @Test
    @DisplayName("요약이 없어도 실패가 아니다")
    void missingSummaryIsNotAFailure() {
        port.json = """
                {"items":[{"content":"항목 하나","category":"PART",
                           "partCode":null,"reason":null}]}
                """;

        RepairChecklistDraft draft = generator.generate(CONTEXT);

        assertThat(draft.summary()).isNull();
        assertThat(draft.items()).hasSize(1);
    }

    @Test
    @DisplayName("같은 문장은 하나만 남는다")
    void duplicateContentIsCollapsed() {
        port.json = """
                {"summary":"요약","items":[
                  {"content":"같은 문장","category":"PART","partCode":null,"reason":null},
                  {"content":"같은 문장","category":"HIDDEN","partCode":null,"reason":"이유"}]}
                """;

        assertThat(generator.generate(CONTEXT).items()).hasSize(1);
    }
}
