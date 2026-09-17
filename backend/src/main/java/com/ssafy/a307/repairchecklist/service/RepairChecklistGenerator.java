package com.ssafy.a307.repairchecklist.service;

import com.ssafy.a307.common.llm.LlmChatPort;
import com.ssafy.a307.repairchecklist.domain.RepairChecklistFailure;
import com.ssafy.a307.repairchecklist.domain.RepairChecklistGenerationException;
import com.ssafy.a307.repairchecklist.entity.RepairChecklistItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 사고별 확인 항목을 LLM 에게 받는다 (S15P21A307-460).
 *
 * <p><b>{@code ReportNarrativeGenerator} 와 실패 처리가 다르다.</b> 거기는 문장이 없어도 PDF 가
 * 나오므로 어떤 실패에도 빈 값을 돌려주지만, <b>여기서는 항목이 결과물 자체다.</b> 빈 목록을
 * 돌려주면 "공통 6종만 든 체크리스트" 가 성공으로 남아, 사용자는 생성이 실패한 줄 모른 채
 * 사고와 무관한 일반론만 들고 정비소에 간다. 그래서 쓸 수 없는 응답은 예외로 올려 보내
 * {@code FAILED} 로 남긴다.
 *
 * <h2>깨진 JSON 에 대한 방어 (S15P21A307-452 가 미배정이다)</h2>
 *
 * <p>AI 쪽 스키마 검증·재시도 스토리가 아직 아무에게도 없다. 백엔드가 스스로 막지 않으면 생성이
 * 간헐적으로 실패하므로 세 겹으로 막는다.
 *
 * <ol>
 *   <li><b>구조화 출력</b> — {@link LlmChatPort.JsonSchema} 로 형식을 고정해 요청한다</li>
 *   <li><b>관대한 해석</b> — 항목 하나가 비거나 문자열이 아니면 <b>그 항목만</b> 버린다.
 *       한 줄 때문에 호출 비용 전체를 버리지 않는다</li>
 *   <li><b>쓸 수 없으면 실패</b> — 파싱이 깨지거나 남은 항목이 0건이면
 *       {@link RepairChecklistFailure#INVALID_RESPONSE}</li>
 * </ol>
 *
 * <p><b>재시도하지 않는다.</b> {@code repair_checklist} 에는 시도 횟수를 셀 열이 없다
 * ({@code estimate_validation} 이 같은 제약으로 자동 재시도를 포기한 것과 같다). 횟수를 못 세는
 * 채로 자동 재시도를 넣으면 <b>영영 실패하는 건이 매 주기 GMS 크레딧을 태운다.</b> 대신 사용자가
 * 생성을 다시 요청하면 {@code FAILED} 행이 큐로 돌아간다({@code RepairChecklistRequestService}) —
 * 재시도 횟수를 사람이 쥐는 셈이고, 자동화하려면 열이 하나 필요하다.
 *
 * <p><b>응답 본문을 로그에 남기지 않는다.</b> 사고 정보에 차주 정보가 섞여 들어올 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepairChecklistGenerator {

    /** 지시문에 실을 손상 부위 수 상한. 전부 넣으면 지시문이 길어지고 크레딧을 더 쓴다. */
    private static final int MAX_PARTS_IN_PROMPT = 20;

    /** 채택할 AI 항목 수 상한. 화면에 다 보이지 않을 만큼 길어지면 체크리스트 구실을 못 한다. */
    private static final int MAX_AI_ITEMS = 10;

    /**
     * 응답 스키마. <b>문장 필드 하나뿐이다.</b> 금액·판정·우선순위 필드를 두지 않았고, 그래서
     * LLM 이 그런 값을 지어내도 담을 자리가 없어 파싱 단계에서 사라진다
     * ({@code ReportNarrativeGenerator.RESPONSE_SCHEMA} 와 같은 방어다).
     */
    private static final String RESPONSE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "items": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "content": { "type": "string" }
                    },
                    "required": ["content"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["items"],
              "additionalProperties": false
            }
            """;

    private final Optional<LlmChatPort> llmChatPort;
    private final ObjectMapper objectMapper;

    /**
     * @return 사고별 확인 항목 문안. <b>비어 있지 않다</b> — 비면 예외를 던진다
     * @throws RepairChecklistGenerationException 호출 실패 또는 쓸 수 없는 응답
     */
    public List<String> generate(RepairChecklistContext context) {
        LlmChatPort port = llmChatPort.orElseThrow(() -> new RepairChecklistGenerationException(
                RepairChecklistFailure.LLM_UNAVAILABLE, "LLM 어댑터가 없다."));

        long startedAt = System.currentTimeMillis();
        LlmChatPort.ChatResult chat;
        try {
            chat = port.complete(LlmChatPort.ChatRequest.textOnly(
                    instruction(context),
                    new LlmChatPort.JsonSchema("repairChecklistItems", RESPONSE_SCHEMA)));
        } catch (RuntimeException e) {
            // 본문을 남기지 않는다. 남기는 것은 예외 종류뿐이다.
            throw new RepairChecklistGenerationException(RepairChecklistFailure.LLM_CALL_FAILED,
                    "체크리스트 생성 호출이 실패했다: " + e.getClass().getSimpleName(), e);
        }

        List<String> items = parse(chat.json());
        log.info("체크리스트 항목 생성 완료: 항목={}건, {}ms", items.size(),
                System.currentTimeMillis() - startedAt);
        return items;
    }

    /**
     * 지시문. <b>확정된 값을 적어 주고 그 값만 쓰라고 못 박는다.</b>
     *
     * <p>표현 제약이 여기 있다 — 이 체크리스트는 사용자가 정비소에 들고 가서 <b>묻는</b> 것이지
     * 정비소를 고발하는 문서가 아니다. {@code ReportNarrativeGenerator} 와
     * {@code RepairShopQuestionGenerator} 가 같은 경계를 지킨다.
     *
     * <p><b>공통 6종을 여기에 적지 않는다.</b> 그 문안은 {@code repair_checklist_common_item} 이
     * 정본이고 코드가 사본을 갖지 않는다. 대신 "일반적인 절차는 빼라" 고만 일러 두어 중복을 줄인다.
     */
    private String instruction(RepairChecklistContext context) {
        return """
                당신은 자동차 사고 수리를 맡기려는 차주가 정비소에서 확인할 항목을 만드는 도구입니다.
                아래는 이미 확정된 사고 정보입니다. 이 정보에 맞는 확인 항목을 만들어 주세요.

                차량: %s %s %s
                손상 부위 분석 결과:
                %s

                규칙:
                - 위에 적힌 정보에만 근거해서 쓰세요. 없는 손상이나 부품을 덧붙이지 마세요.
                - 항목은 최대 %d개입니다. 이 사고에만 해당하는 것부터 쓰세요.
                - 각 항목은 차주가 정비소에서 직접 확인하거나 질문할 수 있는 한 문장입니다.
                  100자 이내로 씁니다.
                - 금액, 수리비, 공임 단가를 적지 마세요. 이 도구는 금액을 판정하지 않습니다.
                - 견적서 서면 수령, 부품 등급 확인, 작업 전후 사진, 교체 부품 실물 확인,
                  보증 기간, 예상 소요 기간처럼 모든 사고에 공통인 절차는 넣지 마세요.
                  그 항목들은 따로 붙습니다.
                - 단정적이거나 비난하는 표현을 쓰지 마세요. 확인을 권한다는 뜻으로만 씁니다.
                - 정비소나 특정 업체를 평가하는 표현을 쓰지 마세요.
                - 의료 판단, 법적 판단, 과실·합의에 대한 조언을 하지 마세요.
                - HTML 태그나 마크다운을 쓰지 마세요. 문장만 씁니다.
                """.formatted(
                blankToUnknown(context.manufacturer()),
                blankToUnknown(context.modelName()),
                context.modelYear() == null ? "" : context.modelYear() + "년식",
                partLines(context),
                MAX_AI_ITEMS);
    }

    /** 분석 결과가 없으면 <b>없다고 적는다.</b> 부위를 지어내지 않는다. */
    private static String partLines(RepairChecklistContext context) {
        List<RepairChecklistContext.DamagedPartView> parts = context.parts();
        if (parts.isEmpty()) {
            return "- 없음 (사진 분석 결과가 아직 없습니다. 차량 정보만으로 만들어 주세요)";
        }
        String body = parts.stream()
                .limit(MAX_PARTS_IN_PROMPT)
                .map(part -> "- 부품코드 %s · 손상유형 %s · 권장수리방식 %s".formatted(
                        part.partCode(), part.damageType(), part.repairMethod()))
                .collect(Collectors.joining("\n"));
        return parts.size() > MAX_PARTS_IN_PROMPT
                ? body + "\n- (외 %d건)".formatted(parts.size() - MAX_PARTS_IN_PROMPT)
                : body;
    }

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "(미상)" : value;
    }

    /**
     * 응답 해석. <b>스키마에 없는 값은 담지 않는다.</b>
     *
     * <p>같은 문장이 두 번 오면 하나만 남긴다 — 중복은 {@code uk_rcli_common} 이 막아 주지 않는다
     * (그 제약은 공통 항목 전용이고 {@code common_code} 가 {@code NULL} 인 AI 항목끼리는 몇 개든
     * 들어간다). 화면에 같은 줄이 두 번 보이는 것을 막는 곳이 여기뿐이다.
     */
    private List<String> parse(String json) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (RuntimeException e) {
            throw new RepairChecklistGenerationException(RepairChecklistFailure.INVALID_RESPONSE,
                    "응답을 JSON 으로 읽지 못했다: " + e.getClass().getSimpleName(), e);
        }

        List<String> contents = new ArrayList<>(new LinkedHashSet<>(collect(root)));
        if (contents.isEmpty()) {
            throw new RepairChecklistGenerationException(RepairChecklistFailure.INVALID_RESPONSE,
                    "쓸 수 있는 항목이 하나도 없다.");
        }
        return contents.size() <= MAX_AI_ITEMS ? contents : contents.subList(0, MAX_AI_ITEMS);
    }

    private static List<String> collect(JsonNode root) {
        List<String> contents = new ArrayList<>();
        for (JsonNode node : root.path("items")) {
            String content = text(node.path("content"));
            if (content == null) continue;
            contents.add(content.length() <= RepairChecklistItem.MAX_CONTENT_LENGTH
                    ? content
                    : content.substring(0, RepairChecklistItem.MAX_CONTENT_LENGTH));
        }
        return contents;
    }

    private static String text(JsonNode node) {
        if (!node.isString()) return null;
        String value = node.asString().strip();
        return value.isEmpty() ? null : value;
    }
}
