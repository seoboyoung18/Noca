package com.ssafy.a307.repairquestion.service;

import com.ssafy.a307.common.llm.LlmChatPort;
import com.ssafy.a307.repairquestion.domain.RepairQuestionFailure;
import com.ssafy.a307.repairquestion.domain.RepairQuestionGenerationException;
import com.ssafy.a307.repairquestion.entity.RepairQuestionItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 정비소에 물어볼 질문을 LLM 에게 받는다 (S15P21A307-476 · -477).
 *
 * <p>구조는 {@code RepairChecklistGenerator} 를 그대로 따랐다 — 구조화 출력 · 관대한 해석 ·
 * 쓸 수 없으면 실패 · 자동 재시도 없음. 다른 점은 둘이고 아래에 적는다.
 *
 * <h2>1. 근거를 함께 받되, 판정은 LLM 이 정하지 않는다</h2>
 *
 * <p>질문 항목은 "어느 부품 · 판정에서 나왔는지" 를 들고 있어야 한다({@code S15P21A307-510}).
 * 그래서 응답 스키마에 {@code partCode} 를 두었지만 <b>{@code damageType} · {@code repairMethod}
 * 필드는 두지 않았다.</b> 그 두 값은 {@code damaged_part} 에서 복사한다 — LLM 이 정하게 두면
 * 분석이 말하지 않은 판정이 사용자에게 사실처럼 보이고, 어휘를 벗어나면
 * {@code ck_rqi_damage} · {@code ck_rqi_method} 가 INSERT 를 거부한다.
 *
 * <p>모르는 {@code partCode} 가 오면 <b>그 질문을 버리지 않고 근거만 비운다.</b> 문장 자체는
 * 쓸 만한데 꼬리표가 틀렸을 뿐이고, 근거 네 열이 전부 {@code NULL} 인 질문은
 * {@code ck_rqi_part} 가 허용하는 정상적인 모양이다.
 *
 * <h2>2. 비난하는 문장을 걸러낸다 (prompt65 §3-3)</h2>
 *
 * <p>이 목록은 차주가 정비소에 들고 가서 <b>묻는</b> 것이지 정비소를 고발하는 문서가 아니다.
 * 경계를 두 겹으로 둔다 — 지시문이 먼저 막고({@link #instruction}), 그래도 나오면
 * {@link #ACCUSATORY} 가 그 줄을 버린다. 어휘는 {@code RepairShopQuestionGenerator}(견적서 검증
 * 쪽)가 쓰는 것과 같은 경계이고, {@code ReportNarrativeGenerator} javadoc 이 "확인 권장 까지" 라고
 * 적어 둔 그 선이다.
 *
 * <p>걸러낸 끝에 0건이 되면 {@link RepairQuestionFailure#INVALID_RESPONSE} 로 실패시킨다.
 * 경계를 넘은 목록을 반쯤 내보내느니 실패로 남기는 편이 낫다.
 *
 * <p><b>응답 본문을 로그에 남기지 않는다.</b> 사고 정보에 차주 정보가 섞여 들어올 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepairQuestionGenerator {

    /** 지시문에 실을 손상 부위 수 상한. 전부 넣으면 지시문이 길어지고 크레딧을 더 쓴다. */
    private static final int MAX_PARTS_IN_PROMPT = 20;

    /** 채택할 질문 수 상한. 화면에서 한눈에 훑고 복사할 수 있는 길이를 넘기지 않는다. */
    private static final int MAX_QUESTIONS = 10;

    /**
     * 비난 · 단정 표현. <b>이 단어가 든 질문은 버린다.</b>
     *
     * <p>{@code RepairShopQuestionGenerator.ACCUSATORY} 와 같은 경계를 지키되, 그쪽은 업로드된
     * 견적서의 <b>항목명</b>에서 단어만 지우면 되는 반면 여기는 <b>문장 전체</b>가 LLM 이 쓴
     * 것이라 단어만 지우면 문장이 망가진다. 그래서 지우지 않고 그 줄을 버린다.
     */
    private static final Pattern ACCUSATORY = Pattern.compile(
            "사기|허위|바가지|눈속임|속이[는던]|부당|과잉\\s*수리|불필요한\\s*수리|불법");

    /** 마크업 제거. 질문은 화면이 그대로 렌더링하는 문자열이다. */
    private static final Pattern SCRIPT_BLOCK = Pattern.compile("(?is)<script[^>]*>.*?</script>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * 응답 스키마. <b>문장과 부품 코드 둘뿐이다.</b> 금액 · 판정 · 우선순위 필드를 두지 않았고,
     * 그래서 LLM 이 그런 값을 지어내도 담을 자리가 없어 파싱 단계에서 사라진다
     * ({@code RepairChecklistGenerator.RESPONSE_SCHEMA} 와 같은 방어다).
     */
    private static final String RESPONSE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "questions": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "content": { "type": "string" },
                      "partCode": { "type": ["string", "null"] }
                    },
                    "required": ["content", "partCode"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["questions"],
              "additionalProperties": false
            }
            """;

    private final Optional<LlmChatPort> llmChatPort;
    private final ObjectMapper objectMapper;

    /**
     * @return 저장할 질문. <b>비어 있지 않다</b> — 비면 예외를 던진다
     * @throws RepairQuestionGenerationException 호출 실패 또는 쓸 수 없는 응답
     */
    public List<RepairQuestionDraft> generate(RepairQuestionContext context) {
        LlmChatPort port = llmChatPort.orElseThrow(() -> new RepairQuestionGenerationException(
                RepairQuestionFailure.LLM_UNAVAILABLE, "LLM 어댑터가 없다."));

        long startedAt = System.currentTimeMillis();
        LlmChatPort.ChatResult chat;
        try {
            chat = port.complete(LlmChatPort.ChatRequest.textOnly(
                    instruction(context),
                    new LlmChatPort.JsonSchema("repairShopQuestions", RESPONSE_SCHEMA)));
        } catch (RuntimeException e) {
            // 본문을 남기지 않는다. 남기는 것은 예외 종류뿐이다.
            throw new RepairQuestionGenerationException(RepairQuestionFailure.LLM_CALL_FAILED,
                    "질문 생성 호출이 실패했다: " + e.getClass().getSimpleName(), e);
        }

        List<RepairQuestionDraft> drafts = parse(chat.json(), context);
        log.info("정비소 확인 질문 생성 완료: 질문={}건(근거 있음 {}건), {}ms",
                drafts.size(), drafts.stream().filter(RepairQuestionDraft::grounded).count(),
                System.currentTimeMillis() - startedAt);
        return drafts;
    }

    /**
     * 지시문. <b>확정된 값을 적어 주고 그 값만 쓰라고 못 박는다.</b>
     *
     * <p>표현 제약이 여기 있다(prompt65 §3-3) — 확인을 <b>권하는</b> 질문까지가 이 서비스가 쓸 수
     * 있는 표현이고, 정비소를 의심 · 비난하거나 잘못을 단정하는 문장은 경계 밖이다.
     * {@code ReportNarrativeGenerator} · {@code RepairShopQuestionGenerator} 가 지키는 같은 선이다.
     *
     * <p><b>금액을 주지 않는다.</b> 예상 견적에서 가져오는 것은 신뢰도 등급뿐이다 — 이유는
     * {@link RepairQuestionContext.EstimateView} 에 적어 두었다.
     */
    private String instruction(RepairQuestionContext context) {
        return """
                당신은 자동차 사고 수리를 맡기려는 차주가 정비소에서 물어볼 질문을 만드는 도구입니다.
                아래는 이미 확정된 사고 정보입니다. 이 정보에 맞는 질문을 만들어 주세요.

                차량: %s %s %s
                손상 부위 분석 결과:
                %s
                예상 견적: %s

                규칙:
                - 위에 적힌 정보에만 근거해서 쓰세요. 없는 손상이나 부품을 덧붙이지 마세요.
                - 질문은 최대 %d개입니다. 이 사고에만 해당하는 것부터 쓰세요.
                - 각 질문은 차주가 정비소에서 그대로 읽어 물어볼 수 있는 완성된 한 문장입니다.
                  100자 이내로 쓰고, 물음표로 끝냅니다.
                - 문장을 조각으로 쪼개지 마세요. 화면에서 이어 붙이지 않고 그대로 복사해 씁니다.
                - 부품 이름은 위에 적힌 한글 이름을 그대로 쓰세요. 영문 코드를 문장에 넣지 마세요.
                - 특정 부품에 대한 질문이면 partCode 에 위 목록의 부품코드를 그대로 적으세요.
                  목록에 없는 코드를 지어내지 마세요. 부품과 무관한 질문이면 partCode 를 null 로 둡니다.
                - 금액, 수리비, 공임 단가를 단정해 적지 마세요. 이 도구는 금액을 판정하지 않습니다.
                  금액을 물어보는 질문은 괜찮습니다.
                - 정비소를 의심하거나 비난하는 표현을 쓰지 마세요. 사기, 허위, 바가지, 부당,
                  과잉 수리, 불필요한 수리 같은 말을 쓰지 않습니다.
                - 잘못을 단정하지 말고, 확인을 부탁하는 정중한 어조로 씁니다.
                - 정비소나 특정 업체를 평가하는 표현을 쓰지 마세요.
                - 의료 판단, 법적 판단, 과실·합의에 대한 조언을 하지 마세요.
                - HTML 태그나 마크다운을 쓰지 마세요. 문장만 씁니다.
                """.formatted(
                blankToUnknown(context.manufacturer()),
                blankToUnknown(context.modelName()),
                context.modelYear() == null ? "" : context.modelYear() + "년식",
                partLines(context),
                estimateLine(context),
                MAX_QUESTIONS);
    }

    /** 분석 결과가 없으면 <b>없다고 적는다.</b> 부위를 지어내지 않는다. */
    private static String partLines(RepairQuestionContext context) {
        List<RepairQuestionContext.DamagedPartView> parts = context.parts();
        if (parts.isEmpty()) {
            return "- 없음 (사진 분석 결과가 아직 없습니다. 차량 정보만으로 만들어 주세요)";
        }
        String body = parts.stream()
                .limit(MAX_PARTS_IN_PROMPT)
                .map(part -> "- 부품코드 %s · 부품이름 %s · 손상유형 %s · 권장수리방식 %s".formatted(
                        part.partCode(), blankToUnknown(part.partName()),
                        blankToUnknown(part.damageType()), blankToUnknown(part.repairMethod())))
                .collect(Collectors.joining("\n"));
        return parts.size() > MAX_PARTS_IN_PROMPT
                ? body + "\n- (외 %d건)".formatted(parts.size() - MAX_PARTS_IN_PROMPT)
                : body;
    }

    /** 예상 견적. <b>금액은 넣지 않는다</b> — 신뢰도만 전한다. */
    private static String estimateLine(RepairQuestionContext context) {
        RepairQuestionContext.EstimateView estimate = context.estimate();
        if (estimate == null) {
            return "아직 없음 (예상 견적을 내지 않은 사고입니다)";
        }
        if (!estimate.estimable()) {
            return "산출 불가 (예상 견적을 낼 수 없었던 사고입니다. 견적 근거를 묻는 질문이 도움이 됩니다)";
        }
        String grade = blankToUnknown(estimate.confidenceGrade());
        return "산출됨 · 신뢰도 등급 %s (등급이 LOW 이면 확인할 거리가 더 많다는 뜻입니다)".formatted(grade);
    }

    private static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "(미상)" : value;
    }

    /**
     * 응답 해석. <b>스키마에 없는 값은 담지 않는다.</b>
     *
     * <p>같은 문장이 두 번 오면 하나만 남긴다 — 질문 쪽에는 중복을 막는 UNIQUE 가 없어
     * (체크리스트의 {@code uk_rcli_common} 에 해당하는 제약이 없다) 화면에 같은 줄이 두 번
     * 보이는 것을 막는 곳이 여기뿐이다.
     */
    private List<RepairQuestionDraft> parse(String json, RepairQuestionContext context) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (RuntimeException e) {
            throw new RepairQuestionGenerationException(RepairQuestionFailure.INVALID_RESPONSE,
                    "응답을 JSON 으로 읽지 못했다: " + e.getClass().getSimpleName(), e);
        }

        Map<String, RepairQuestionContext.DamagedPartView> knownParts = context.parts().stream()
                .collect(Collectors.toMap(RepairQuestionContext.DamagedPartView::partCode,
                        Function.identity(), (first, second) -> first, LinkedHashMap::new));

        Map<String, RepairQuestionDraft> byContent = new LinkedHashMap<>();
        int dropped = 0;
        for (JsonNode node : root.path("questions")) {
            String content = sanitize(text(node.path("content")));
            if (content == null) continue;
            if (ACCUSATORY.matcher(content).find()) {
                // 문장 전체가 LLM 이 쓴 것이라 단어만 지우면 문장이 망가진다. 줄째로 버린다.
                dropped++;
                continue;
            }
            byContent.putIfAbsent(content,
                    new RepairQuestionDraft(content, knownParts.get(text(node.path("partCode")))));
        }
        if (dropped > 0) {
            log.warn("비난 표현이 섞인 질문을 버렸다: {}건", dropped);
        }

        List<RepairQuestionDraft> drafts = new ArrayList<>(byContent.values());
        if (drafts.isEmpty()) {
            throw new RepairQuestionGenerationException(RepairQuestionFailure.INVALID_RESPONSE,
                    "쓸 수 있는 질문이 하나도 없다.");
        }
        return drafts.size() <= MAX_QUESTIONS ? drafts : drafts.subList(0, MAX_QUESTIONS);
    }

    private static String sanitize(String value) {
        if (value == null) return null;
        String sanitized = SCRIPT_BLOCK.matcher(value).replaceAll(" ");
        sanitized = TAG.matcher(sanitized).replaceAll(" ");
        sanitized = WHITESPACE.matcher(sanitized).replaceAll(" ").strip();
        if (sanitized.isEmpty()) return null;
        return sanitized.length() <= RepairQuestionItem.MAX_CONTENT_LENGTH
                ? sanitized
                : sanitized.substring(0, RepairQuestionItem.MAX_CONTENT_LENGTH);
    }

    private static String text(JsonNode node) {
        if (!node.isString()) return null;
        String value = node.asString().strip();
        return value.isEmpty() ? null : value;
    }
}
