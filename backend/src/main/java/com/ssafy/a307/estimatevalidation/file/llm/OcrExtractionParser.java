package com.ssafy.a307.estimatevalidation.file.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ssafy.a307.estimatevalidation.domain.WorkTypeMapper;
import com.ssafy.a307.estimatevalidation.file.EstimateOcrPort;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 모델이 낸 텍스트를 {@link EstimateOcrPort.OcrExtraction} 으로 바꾼다.
 *
 * <p><b>항목 단위로 버리고 계속한다.</b> {@code OcrLineItem} 의 컴팩트 생성자는 규칙을 어기면
 * 예외를 던지므로, 한 줄만 잘못 읽어도 견적서 전체가 날아간다. 이 저장소는 20장 중 3장이
 * 실패해도 17장을 살리는 쪽을 택해 왔다({@code ImageUploadCompleteResponse} 의 부분 실패 허용).
 * 같은 판단을 여기에도 적용한다.
 *
 * <p>다만 <b>통과한 항목이 0개면 빈 결과를 그대로 돌려준다.</b> 그 뒤는 워커가 판단한다 —
 * 빈 견적서를 "적정" 으로 판정하지 않고 검증을 실패로 끝낸다.
 *
 * <p><b>버린 항목의 내용은 로그에 남기지 않는다.</b> 개수와 사유만 남긴다. 견적서 항목에는
 * 차주 이름·차량번호가 섞여 들어올 수 있어 원문을 로그 파일에 흘리면 안 된다.
 */
@Slf4j
public class OcrExtractionParser {

    private static final int MAX_SMALLINT = 32767;
    private static final int MAX_ITEM_NAME_LENGTH = 200;
    private static final int MAX_ITEMS = 200;

    private final ObjectMapper objectMapper;
    private final double minConfidence;

    public OcrExtractionParser(ObjectMapper objectMapper, double minConfidence) {
        this.objectMapper = objectMapper;
        this.minConfidence = minConfidence;
    }

    /**
     * @param rawText 모델이 낸 텍스트. 코드펜스가 붙어 오는 일이 흔해 먼저 벗긴다
     * @throws IllegalArgumentException JSON 자체를 읽을 수 없을 때. 판독 실패로 이어진다
     */
    public EstimateOcrPort.OcrExtraction parse(String rawText) {
        JsonNode root = readJson(rawText);
        JsonNode itemsNode = root.path("items");
        if (!itemsNode.isArray()) {
            throw new IllegalArgumentException("판독 응답에 items 배열이 없다");
        }

        List<EstimateOcrPort.OcrLineItem> items = new ArrayList<>();
        Set<Integer> usedLineNumbers = new HashSet<>();
        int discardedInvalid = 0;
        int discardedLowConfidence = 0;
        int discardedDuplicate = 0;

        for (JsonNode node : itemsNode) {
            if (items.size() >= MAX_ITEMS) {
                discardedInvalid += 1;
                continue;
            }
            Candidate candidate = candidateOf(node);
            if (candidate == null) {
                discardedInvalid++;
                continue;
            }
            if (candidate.confidence() < minConfidence) {
                discardedLowConfidence++;
                continue;
            }
            if (!usedLineNumbers.add(candidate.lineNo())) {
                discardedDuplicate++;
                continue;
            }
            items.add(candidate.toLineItem());
        }

        int discarded = discardedInvalid + discardedLowConfidence + discardedDuplicate;
        if (discarded > 0) {
            log.warn("판독 항목 {}건을 버렸다 (형식·범위 {}건, 신뢰도 미달 {}건, 행번호 중복 {}건). 남은 항목 {}건",
                    discarded, discardedInvalid, discardedLowConfidence, discardedDuplicate, items.size());
        }
        return new EstimateOcrPort.OcrExtraction(items, documentTotal(root), documentConfidence(root));
    }

    private JsonNode readJson(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("판독 응답이 비어 있다");
        }
        try {
            return objectMapper.readTree(stripFence(rawText));
        } catch (Exception e) {
            // 응답 본문을 메시지에 넣지 않는다. 견적서 내용이 그대로 로그에 남는다.
            throw new IllegalArgumentException("판독 응답을 JSON 으로 읽지 못했다", e);
        }
    }

    /**
     * ```json … ``` 처럼 코드펜스로 감싸 오는 경우를 벗긴다.
     *
     * <p>지시문에서 붙이지 말라고 했지만 모델은 자주 붙인다. 여기서 한 줄 벗기는 비용이
     * 판독 전체를 실패시키는 것보다 훨씬 싸다. 앞뒤 설명이 붙은 경우까지 보려고
     * 가장 바깥 중괄호 구간을 잘라 쓴다.
     */
    private static String stripFence(String rawText) {
        String text = rawText.strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    /** 규칙을 어긴 항목은 {@code null} 로 돌려 버리게 한다. 예외로 만들면 전체가 죽는다. */
    private Candidate candidateOf(JsonNode node) {
        if (!node.isObject()) return null;
        int lineNo = node.path("lineNo").asInt(0);
        String rawItemName = node.path("rawItemName").asText("").strip();
        String workType = node.path("workType").asText("").strip();
        long quantity = node.path("quantity").asLong(1);
        long partCost = node.path("partCost").asLong(-1);
        long laborCost = node.path("laborCost").asLong(-1);
        double confidence = node.path("confidence").asDouble(0.0);

        if (lineNo < 1 || lineNo > MAX_SMALLINT) return null;
        if (rawItemName.isEmpty() || rawItemName.length() > MAX_ITEM_NAME_LENGTH) return null;
        // 모델이 냈다고 작업유형 검증을 느슨하게 하지 않는다. 직접 입력과 같은 매퍼를 쓴다.
        if (WorkTypeMapper.from(workType).isEmpty()) return null;
        if (quantity < 1 || quantity > MAX_SMALLINT) return null;
        if (partCost < 0 || laborCost < 0) return null;
        if (partCost > Integer.MAX_VALUE || laborCost > Integer.MAX_VALUE) return null;
        // 부품비와 공임이 둘 다 0이면 EstimateLine 이 거절한다. 여기서 미리 버린다.
        if (partCost == 0 && laborCost == 0) return null;
        if (confidence < 0 || confidence > 1) return null;
        if (overflowsSubtotal(partCost, laborCost, quantity)) return null;

        return new Candidate(lineNo, rawItemName, workType, quantity, partCost, laborCost, confidence);
    }

    /**
     * 소계가 {@code estimate_validation_item.subtotal INTEGER} 에 들어가는지 본다.
     *
     * <p>{@code EstimateValidationItem.from} 이 {@code Math.toIntExact} 로 좁히기 때문에,
     * int 를 넘는 소계는 저장 직전에 {@code ArithmeticException} 으로 터진다. 항목 하나 때문에
     * 견적서 전체가 죽지 않도록 여기서 미리 버린다.
     */
    private static boolean overflowsSubtotal(long partCost, long laborCost, long quantity) {
        try {
            Math.toIntExact(Math.multiplyExact(Math.addExact(partCost, laborCost), quantity));
            return false;
        } catch (ArithmeticException e) {
            return true;
        }
    }

    /** 문서에 적힌 총액. <b>참고용이다</b> — 실제 총액은 항목 소계 합계를 쓴다. */
    private static Long documentTotal(JsonNode root) {
        JsonNode node = root.path("documentTotal");
        if (!node.isNumber()) return null;
        long value = node.asLong();
        return value > 0 ? value : null;
    }

    private static double documentConfidence(JsonNode root) {
        double value = root.path("documentConfidence").asDouble(0.0);
        return value < 0 || value > 1 ? 0.0 : value;
    }

    private record Candidate(
            int lineNo, String rawItemName, String workType,
            long quantity, long partCost, long laborCost, double confidence) {

        EstimateOcrPort.OcrLineItem toLineItem() {
            return new EstimateOcrPort.OcrLineItem(
                    lineNo, rawItemName, workType, quantity, partCost, laborCost, null, confidence);
        }
    }
}
