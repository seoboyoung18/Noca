package com.ssafy.a307.estimate.narrative;

import com.ssafy.a307.common.llm.LlmChatPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 견적 리포트에 실을 문장을 LLM 에게 받는다 (S15P21A307-537).
 *
 * <h2>LLM 이 만드는 것과 만들지 않는 것</h2>
 *
 * <p>금액·등급·판정은 이미 규칙과 AI 산정이 정했고, 이 클래스는 <b>그 값을 읽는 문장</b>만
 * 받는다. 응답 스키마에 금액 필드가 없어 지어낸 숫자는 담길 자리가 없다
 * ({@code ReportNarrativeGenerator} 와 같은 방어다).
 *
 * <p>그래도 <b>문장 안에 숫자를 지어낼 수는 있다.</b> 근거 문장은 규칙이 만든 원문을 주고 표현만
 * 다듬게 하는데, 다듬은 결과에 원문에 없던 숫자가 섞이면 그 문장을 버린다
 * ({@link #keepsNumbers}). 버려진 자리는 규칙 문장이 그대로 나가므로 사용자가 보는 근거는
 * 언제나 저장된 값과 일치한다.
 *
 * <h2>실패해도 리포트는 나간다</h2>
 *
 * <p>여기서 던지는 예외는 요약 한 건의 실패다. 리포트는 요약 없이도 완성되며
 * ({@code EstimateReportService.requireSections} 가 요약을 필수로 보지 않는다), 화면과 PDF 는
 * 그 자리를 비운다. <b>체크리스트와 다른 점이다</b> — 거기서는 항목이 결과물 자체였다.
 *
 * <p><b>응답 본문을 로그에 남기지 않는다.</b> 견적에는 차종·금액이 들어 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EstimateNarrativeGenerator {

    /** 확인 권장 줄 수 상한. 길어지면 체크리스트와 구분이 사라진다. */
    private static final int MAX_CAUTIONS = 3;

    /** 지시문에 실을 항목 수 상한. 전부 넣으면 지시문이 길어지고 크레딧을 더 쓴다. */
    private static final int MAX_ITEMS_IN_PROMPT = 15;

    /** {@code summary} 최대 길이. 열은 더 넓지만 화면 상단에 들어갈 분량으로 끊는다. */
    private static final int MAX_SUMMARY_LENGTH = 500;

    private static final int MAX_CAUTION_LENGTH = 150;
    private static final int MAX_NOTE_LENGTH = 300;

    /** 숫자 검증용. 천 단위 구분기호는 빼고 숫자만 본다. */
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /**
     * 응답 스키마. <b>금액·등급·판정 필드가 없다.</b>
     *
     * <p>OpenAI {@code strict: true} 가 모든 필드를 {@code required} 로 요구하므로, "없을 수
     * 있다" 는 {@code ["string","null"]} 로 표현한다({@code StructuredOutputSchemaTest}).
     */
    private static final String RESPONSE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "summary": { "type": "string" },
                "cautions": {
                  "type": "array",
                  "items": { "type": "string" }
                },
                "basisNotes": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "partCode": { "type": "string" },
                      "text": { "type": "string" }
                    },
                    "required": ["partCode", "text"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["summary", "cautions", "basisNotes"],
              "additionalProperties": false
            }
            """;

    private final Optional<LlmChatPort> llmChatPort;
    private final ObjectMapper objectMapper;

    /**
     * @return 리포트에 실을 문장. <b>비어 있지 않다</b> — 쓸 문장이 하나도 없으면 예외를 던진다
     * @throws EstimateNarrativeGenerationException 어댑터 없음 · 호출 실패 · 쓸 수 없는 응답
     */
    public EstimateNarrativeContent generate(EstimateNarrativeContext context) {
        LlmChatPort port = llmChatPort.orElseThrow(() -> new EstimateNarrativeGenerationException(
                EstimateNarrativeFailure.LLM_UNAVAILABLE, "LLM 어댑터가 없다."));

        long startedAt = System.currentTimeMillis();
        LlmChatPort.ChatResult chat;
        try {
            chat = port.complete(LlmChatPort.ChatRequest.textOnly(
                    instruction(context),
                    new LlmChatPort.JsonSchema("estimateReportNarrative", RESPONSE_SCHEMA)));
        } catch (RuntimeException e) {
            // 본문을 남기지 않는다. 남기는 것은 예외 종류뿐이다.
            throw new EstimateNarrativeGenerationException(EstimateNarrativeFailure.LLM_CALL_FAILED,
                    "견적 요약 호출이 실패했다: " + e.getClass().getSimpleName(), e);
        }

        EstimateNarrativeContent content = parse(chat.json(), context);
        log.info("견적 요약 생성 완료: 확인권장={}건, 근거문장={}건, 요약={}, {}ms",
                content.cautions().size(), content.basisNotes().size(),
                content.summary() == null ? "없음" : "있음",
                System.currentTimeMillis() - startedAt);
        return content;
    }

    /**
     * 지시문. <b>확정된 값을 적어 주고 그 값만 쓰라고 못 박는다.</b>
     *
     * <p>표현 제약은 {@code ReportNarrativeGenerator} · {@code RepairChecklistGenerator} 와 같은
     * 선이다 — 이 문서는 사용자가 정비소·보험사에 들고 가는 것이지 그들을 고발하는 문서가 아니다.
     *
     * <p><b>신뢰도 등급을 넘기지 않는다</b> (S15P21A307-547). 넘겼더니 "신뢰도는 LOW이므로" 가
     * 요약에 그대로 실렸는데, 하단 고지가 이미 참고용 추정치임을 밝히고 있어 같은 말을 두 번
     * 하는 셈이었다. 등급이 필요한 화면은 {@code EstimateResponse} 로 따로 받는다.
     *
     * <p><b>금액은 넘기되 문장에 옮겨 적지 못하게 한다.</b> 값을 알아야 "경미하다" 와 "큰
     * 수리다" 를 가려 쓸 수 있지만, 숫자를 다시 적으면 바로 위 표와 같은 말이 두 번 나온다 —
     * {@code EstimateReportResponse.Narrative} 가 애초에 "숫자가 없다" 고 정한 자리다.
     */
    private String instruction(EstimateNarrativeContext context) {
        return """
                당신은 자동차 수리 예상 견적 리포트에 들어갈 안내 문장을 쓰는 도구입니다.
                아래는 이미 확정된 값입니다. 계산하지 말고 이 값에 근거해서만 쓰세요.

                차량: %s %s %s
                견적: %s
                참조한 유사 사례: %s

                항목:
                %s

                총액에서 빠진 부위:
                %s

                써야 할 것:
                1) summary — 이 견적을 어떻게 읽어야 하는지 두세 문장. 200자 이내.
                   무엇이 손상됐고 어떤 수리 방식인지, 이 추정치를 무엇에 쓰면 되는지 적습니다.
                   산정하지 못한 견적이면 왜 그런지와 다음에 무엇을 하면 되는지를 적습니다.
                2) cautions — 지금 확인해 두면 좋은 것 %d개 이내. 한 줄에 한 가지, 60자 이내.
                3) basisNotes — 위 항목의 "근거" 문장을 더 읽기 쉽게 다듬은 것.
                   partCode 는 위 목록에 있는 것만 씁니다. 근거가 없는 항목은 넣지 마세요.

                규칙:
                - 위에 적힌 정보에만 근거해서 쓰세요. 없는 손상이나 부품을 덧붙이지 마세요.
                - 금액을 새로 계산하거나 바꾸지 마세요. 숫자는 위에 적힌 그대로 씁니다.
                - summary 와 cautions 에는 금액을 옮겨 적지 마세요. 바로 위 표에 이미 있습니다.
                  문장은 그 숫자를 어떻게 읽을지만 말합니다.
                - basisNotes 는 원문에 없는 숫자를 넣지 마세요. 표현만 다듬습니다.
                - 이 금액은 예상값입니다. 확정 금액이나 보상 금액처럼 쓰지 마세요.
                - 정비소나 보험사를 의심하거나 비난하는 표현을 쓰지 마세요. 사기, 허위, 바가지,
                  과잉 수리 같은 말을 쓰지 않습니다. 확인을 권한다는 뜻으로만 씁니다.
                - 의료 판단, 법적 판단, 과실·합의에 대한 조언을 하지 마세요.
                - HTML 태그나 마크다운을 쓰지 마세요. 문장만 씁니다.
                """.formatted(
                blankToUnknown(context.manufacturer()),
                blankToUnknown(context.modelName()),
                context.modelYear() == null ? "" : context.modelYear() + "년식",
                totalLine(context),
                context.refCaseTotal() == null ? "(미상)" : context.refCaseTotal() + "건",
                itemLines(context),
                unresolvedLines(context),
                MAX_CAUTIONS);
    }

    /** 산정 불가면 금액 대신 사유를 적는다. <b>0원이라고 쓰지 않는다.</b> */
    private static String totalLine(EstimateNarrativeContext context) {
        if (!context.estimable()) {
            return "산정하지 못함 (사유 코드 %s)".formatted(blankToUnknown(context.nonEstimableReason()));
        }
        return "최소 %s원 · 중앙값 %s원 · 최대 %s원".formatted(
                money(context.totalMin()), money(context.totalMedian()), money(context.totalMax()));
    }

    private static String itemLines(EstimateNarrativeContext context) {
        if (context.items().isEmpty()) {
            return "- 없음 (산정된 항목이 없습니다)";
        }
        String body = context.items().stream()
                .limit(MAX_ITEMS_IN_PROMPT)
                .map(item -> "- 부품코드 %s · 부위 %s · 수리방식 %s · 금액 %s원 · 참조 사례 %s건%s".formatted(
                        item.partCode(), blankToUnknown(item.partNameKo()),
                        blankToUnknown(item.repairMethodDisplayName()), money(item.itemMedian()),
                        item.refCaseCount() == null ? "?" : item.refCaseCount(),
                        item.basisNarrative() == null || item.basisNarrative().isBlank()
                                ? " · 근거 없음"
                                : "%n  근거: %s".formatted(item.basisNarrative())))
                .collect(Collectors.joining("\n"));
        return context.items().size() > MAX_ITEMS_IN_PROMPT
                ? body + "\n- (외 %d건)".formatted(context.items().size() - MAX_ITEMS_IN_PROMPT)
                : body;
    }

    private static String unresolvedLines(EstimateNarrativeContext context) {
        if (context.unresolved().isEmpty()) {
            return "- 없음";
        }
        return context.unresolved().stream()
                .map(part -> "- %s (%s)".formatted(
                        part.partNameKo() == null ? part.partCode() : part.partNameKo(),
                        part.reasonDisplayName() == null ? "사유 미상" : part.reasonDisplayName()))
                .collect(Collectors.joining("\n"));
    }

    private static String money(Integer value) {
        return value == null ? "(미상)" : String.format("%,d", value);
    }

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "(미상)" : value;
    }

    // ── 응답 해석 ───────────────────────────────────────────────────────────

    /**
     * 응답 해석. <b>스키마에 없는 값은 담지 않고, 지어낸 숫자는 버린다.</b>
     *
     * @throws EstimateNarrativeGenerationException 파싱 실패 또는 남은 문장이 하나도 없을 때
     */
    private EstimateNarrativeContent parse(String json, EstimateNarrativeContext context) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (RuntimeException e) {
            throw new EstimateNarrativeGenerationException(EstimateNarrativeFailure.INVALID_RESPONSE,
                    "응답을 JSON 으로 읽지 못했다: " + e.getClass().getSimpleName(), e);
        }

        EstimateNarrativeContent content = new EstimateNarrativeContent(
                truncate(text(root.path("summary")), MAX_SUMMARY_LENGTH),
                cautions(root.path("cautions")),
                basisNotes(root.path("basisNotes"), context));

        if (content.isEmpty()) {
            throw new EstimateNarrativeGenerationException(EstimateNarrativeFailure.INVALID_RESPONSE,
                    "쓸 수 있는 문장이 하나도 없다.");
        }
        return content;
    }

    private static List<String> cautions(JsonNode array) {
        Set<String> unique = new LinkedHashSet<>();
        for (JsonNode node : array) {
            String value = truncate(text(node), MAX_CAUTION_LENGTH);
            if (value != null) {
                unique.add(value);
            }
            if (unique.size() >= MAX_CAUTIONS) {
                break;
            }
        }
        return List.copyOf(unique);
    }

    /**
     * 항목별 근거 문장. <b>두 겹으로 거른다.</b>
     *
     * <ol>
     *   <li>지시문에 근거를 적어 주지 않은 부위는 버린다 — 다듬을 원문이 없으면 그 문장은
     *       LLM 이 지어낸 것이다</li>
     *   <li>원문에 없던 숫자가 섞이면 버린다 ({@link #keepsNumbers})</li>
     * </ol>
     */
    private static List<EstimateNarrativeContent.BasisNote> basisNotes(JsonNode array,
                                                                      EstimateNarrativeContext context) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (EstimateNarrativeContext.ItemView item : context.items()) {
            if (item.partCode() != null && item.basisNarrative() != null && !item.basisNarrative().isBlank()) {
                sources.putIfAbsent(item.partCode(), item.basisNarrative());
            }
        }

        Map<String, EstimateNarrativeContent.BasisNote> byPart = new LinkedHashMap<>();
        int dropped = 0;
        for (JsonNode node : array) {
            String partCode = text(node.path("partCode"));
            String value = truncate(text(node.path("text")), MAX_NOTE_LENGTH);
            String source = partCode == null ? null : sources.get(partCode);
            if (value == null || source == null) {
                continue;
            }
            if (!keepsNumbers(source, value)) {
                // 지어낸 숫자다. 규칙이 만든 원문이 그 자리에 그대로 나간다.
                dropped++;
                continue;
            }
            byPart.putIfAbsent(partCode, new EstimateNarrativeContent.BasisNote(partCode, value));
        }
        if (dropped > 0) {
            log.warn("견적 요약 근거 문장 {}건을 버렸다 — 원문에 없는 숫자가 섞였다.", dropped);
        }
        return List.copyOf(byPart.values());
    }

    /**
     * 다듬은 문장의 숫자가 전부 원문에 있는가.
     *
     * <p>숫자를 <b>지우는</b> 것은 허용한다("30건의 중앙값" → "여러 사례의 중앙값"). 없던 숫자를
     * <b>더하는</b> 것만 막는다 — 그것이 협상 근거가 되는 쪽이다.
     */
    private static boolean keepsNumbers(String source, String polished) {
        Set<String> allowed = digitsOf(source);
        for (String number : digitsOf(polished)) {
            if (!allowed.contains(number)) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> digitsOf(String value) {
        Set<String> numbers = new LinkedHashSet<>();
        Matcher matcher = DIGITS.matcher(value.replace(",", ""));
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
        return numbers;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String text(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() ? null : value;
    }

}
