package com.ssafy.a307.estimate.narrative;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 저장된 요약 JSON 을 읽는다 (S15P21A307-537).
 *
 * <p><b>조회를 죽이지 않는다.</b> 열이 비었거나 JSON 이 깨졌으면 빈 값을 돌려준다 — 요약 하나
 * 때문에 리포트 전체가 500 이 되면 사용자는 견적을 못 본다
 * ({@code UnresolvedPartsReader} 와 같은 판단, S15P21A307-534).
 *
 * <p><b>본문을 로그에 남기지 않는다.</b> 견적 요약에는 차종·금액이 들어 있다. 남기는 것은
 * 길이뿐이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EstimateNarrativeReader {

    private final ObjectMapper objectMapper;

    public EstimateNarrativeContent read(String json) {
        if (json == null || json.isBlank()) {
            return EstimateNarrativeContent.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            return new EstimateNarrativeContent(text(root.path("summary")),
                    strings(root.path("cautions")), notes(root.path("basisNotes")));
        } catch (RuntimeException e) {
            log.warn("견적 요약 JSON 을 읽지 못했다. 길이={} 오류={}", json.length(), e.getClass().getSimpleName());
            return EstimateNarrativeContent.empty();
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode node : array) {
            String value = text(node);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    /** 부위가 없는 문장은 버린다 — 어느 항목에 붙일지 알 수 없다. */
    private static List<EstimateNarrativeContent.BasisNote> notes(JsonNode array) {
        List<EstimateNarrativeContent.BasisNote> notes = new ArrayList<>();
        for (JsonNode node : array) {
            String partCode = text(node.path("partCode"));
            String value = text(node.path("text"));
            if (partCode != null && value != null) {
                notes.add(new EstimateNarrativeContent.BasisNote(partCode, value));
            }
        }
        return notes;
    }

    private static String text(JsonNode node) {
        if (!node.isString()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() ? null : value;
    }
}
