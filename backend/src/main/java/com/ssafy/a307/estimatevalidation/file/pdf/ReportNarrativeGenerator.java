package com.ssafy.a307.estimatevalidation.file.pdf;

import com.ssafy.a307.common.llm.LlmChatPort;
import com.ssafy.a307.estimatevalidation.dto.ValidationResultResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 파이프라인 ① — GMS 에게 <b>자연어만</b> 받는다.
 *
 * <p><b>LLM 에게 숫자·등급·판정을 요구하지 않는다.</b> 지시문에 이미 확정된 값을 전부 적어 주고
 * "이 값을 문장으로 옮기라" 고만 시킨다. 응답 스키마에도 숫자 필드가 없다 —
 * <b>요구하지 않은 값이 와도 담을 자리가 없어 그대로 버려진다.</b>
 *
 * <p><b>실패해도 PDF 생성을 멈추지 않는다.</b> 어댑터가 없거나 호출이 실패하면
 * {@link ReportNarrative#empty()} 를 돌려주고, PDF 는 규칙 기반 요약과 사유만으로 생성된다.
 * 문장을 예쁘게 다듬는 일 때문에 사용자가 문서를 못 받으면 안 된다.
 *
 * <p><b>응답 본문을 로그에 남기지 않는다.</b> 견적서 항목명에 차주 정보가 섞여 들어올 수 있다.
 *
 * <p><b>판정 문구 제약을 지시문에 넣는다.</b> LLM 이 "과다청구입니다" 를 쓰면 그대로 PDF 에
 * 인쇄되고, PDF 는 화면보다 오래 남는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportNarrativeGenerator {

    /** 지시문에 실을 항목 수 상한. 전부 넣으면 지시문이 길어지고 크레딧을 더 쓴다. */
    private static final int MAX_ITEMS_IN_PROMPT = 20;

    /**
     * 응답 스키마. <b>문장 필드만 있다.</b> 숫자·등급·판정 필드를 두지 않은 것이 핵심이고,
     * 그래서 LLM 이 값을 지어내도 파싱 단계에서 사라진다.
     */
    private static final String RESPONSE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "gradeExplanation": { "type": "string" },
                "itemNotes": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "lineNo": { "type": "integer" },
                      "note":   { "type": "string" }
                    },
                    "required": ["lineNo", "note"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["gradeExplanation", "itemNotes"],
              "additionalProperties": false
            }
            """;

    private final Optional<LlmChatPort> llmChatPort;
    private final ObjectMapper objectMapper;

    /**
     * @return LLM 이 만든 문장. <b>어떤 실패에도 예외를 던지지 않는다</b> — 빈 값을 돌려준다
     */
    public ReportNarrative generate(ValidationResultResponse result) {
        if (llmChatPort.isEmpty()) {
            log.debug("LLM 어댑터가 없어 규칙 기반 문장만 쓴다. validationId={}", result.validationId());
            return ReportNarrative.empty();
        }
        long startedAt = System.currentTimeMillis();
        try {
            LlmChatPort.ChatResult chat = llmChatPort.get().complete(
                    LlmChatPort.ChatRequest.textOnly(
                            instruction(result),
                            new LlmChatPort.JsonSchema("validationReportNarrative", RESPONSE_SCHEMA)));
            ReportNarrative narrative = parse(chat.json());
            log.info("PDF 문장 생성 완료: validationId={}, 항목문장={}건, {}ms",
                    result.validationId(), narrative.itemNotes().size(),
                    System.currentTimeMillis() - startedAt);
            return narrative;
        } catch (RuntimeException e) {
            // 본문을 남기지 않는다. 남기는 것은 예외 종류뿐이다.
            log.warn("PDF 문장 생성에 실패해 규칙 기반 문장만 쓴다. validationId={}, 원인={}",
                    result.validationId(), e.getClass().getSimpleName());
            return ReportNarrative.empty();
        }
    }

    /**
     * 지시문. <b>확정된 값을 전부 적어 주고 그 값만 쓰라고 못 박는다.</b>
     *
     * <p>판정 문구 제약이 여기 있다 — "확인 권장" 까지가 이 서비스가 쓸 수 있는 표현이고,
     * 그 경계는 {@code RepairShopQuestionGenerator} 가 비난 표현을 걸러내는 것과 같은 판단이다.
     */
    private String instruction(ValidationResultResponse result) {
        return """
                당신은 자동차 수리 견적 검증 결과를 사용자에게 설명하는 도구입니다.
                아래는 이미 계산이 끝난 결과입니다. 이 값을 한국어 문장으로 옮겨 주세요.

                판정 등급: %s
                정비소 견적 총액: %,d원
                전체 항목 수: %d개
                확인을 권장하는 항목 수: %d개
                %s

                항목별 확정 판정:
                %s

                규칙:
                - 위에 적힌 값만 사용하세요. 금액, 등급, 항목 수를 새로 계산하거나 바꾸지 마세요.
                - 위에 없는 사실을 덧붙이거나 추측하지 마세요.
                - gradeExplanation 은 등급을 풀어 설명하는 두세 문장, 200자 이내로 씁니다.
                - itemNotes 는 확인을 권장하는 항목에 대해서만, 왜 확인이 필요한지 한 문장으로
                  씁니다. 100자 이내입니다. 확인이 필요 없는 항목은 넣지 마세요.
                - "부당청구", "과다청구", "바가지", "사기" 같은 단정적 표현을 쓰지 마세요.
                  확인을 권한다는 뜻으로만 쓰세요.
                - 정비소나 특정 업체를 평가하거나 비난하는 표현을 쓰지 마세요.
                  이 결과는 참고용 추정치이며 사업자를 평가하지 않습니다.
                - 의료 판단, 법적 판단, 과실·합의에 대한 조언을 하지 마세요.
                - HTML 태그나 마크다운을 쓰지 마세요. 문장만 씁니다.
                """.formatted(
                result.gradeDisplayName(),
                result.claimedTotal() == null ? 0 : result.claimedTotal(),
                result.totalItemCount(),
                result.reviewItemCount(),
                aiComparisonLine(result),
                itemLines(result));
    }

    /** AI 예상 견적이 없으면 <b>없다고 적는다.</b> 0 으로 채우지 않는다. */
    private static String aiComparisonLine(ValidationResultResponse result) {
        if (result.aiTotalMedian() == null) {
            return "AI 예상 견적: 없음 (분석이 연결되지 않아 비교하지 않았습니다)";
        }
        return "AI 예상 견적 중앙값: %,d원 (정비소 견적과의 차액: %,d원)"
                .formatted(result.aiTotalMedian(),
                        result.differenceFromMedian() == null ? 0L : result.differenceFromMedian());
    }

    private static String itemLines(ValidationResultResponse result) {
        List<ValidationResultResponse.Item> items = result.items();
        if (items.isEmpty()) return "- 없음";
        String body = items.stream()
                .limit(MAX_ITEMS_IN_PROMPT)
                .map(item -> "- %d행 %s (%s): 소계 %,d원 · 판정 %s%s".formatted(
                        item.lineNo(), item.rawItemName(),
                        item.workType() == null ? "-" : item.workType(),
                        item.subtotal() == null ? 0 : item.subtotal(),
                        item.displayDecision(),
                        item.reason() == null ? "" : " · " + item.reason()))
                .collect(Collectors.joining("\n"));
        return items.size() > MAX_ITEMS_IN_PROMPT
                ? body + "\n- (외 %d건)".formatted(items.size() - MAX_ITEMS_IN_PROMPT)
                : body;
    }

    /**
     * 응답 해석. <b>스키마에 없는 값은 담지 않는다.</b>
     *
     * <p>{@code lineNo} 가 정수가 아니거나 문장이 비면 그 항목만 버린다 — 한 줄 때문에
     * 문장 전체를 버리면 LLM 호출 비용이 그대로 낭비된다.
     */
    private ReportNarrative parse(String json) {
        JsonNode root = objectMapper.readTree(json);
        String explanation = text(root.path("gradeExplanation"));
        Map<Integer, String> notes = new LinkedHashMap<>();
        for (JsonNode node : root.path("itemNotes")) {
            JsonNode lineNo = node.path("lineNo");
            String note = text(node.path("note"));
            if (!lineNo.isIntegralNumber() || note == null) continue;
            notes.put(lineNo.asInt(), note);
        }
        return new ReportNarrative(explanation, new HashMap<>(notes));
    }

    private static String text(JsonNode node) {
        if (!node.isString()) return null;
        String value = node.asString().strip();
        return value.isEmpty() ? null : value;
    }
}
