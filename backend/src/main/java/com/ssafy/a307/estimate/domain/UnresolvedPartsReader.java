package com.ssafy.a307.estimate.domain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * {@code estimate.unresolved_parts} 의 JSON 을 {@link UnresolvedPart} 목록으로 읽는다
 * (S15P21A307-534).
 *
 * <p><b>읽지 못해도 예외를 던지지 않는다.</b> {@link RefConditionReader} 와 같은 판단이다 —
 * 안내 한 줄이 깨졌다고 견적 조회 전체를 500 으로 끊으면 사용자는 금액도 항목도 못 본다.
 *
 * <p><b>원문을 로그에 남기지 않는다.</b> 길이만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UnresolvedPartsReader {

    private final ObjectMapper objectMapper;

    /**
     * @param json 열 원문. 이 열이 생기기 전 견적은 {@code null} 이다
     * @return 읽은 부위. 비었거나 읽지 못하면 빈 목록 — {@code null} 을 돌려주지 않는다.
     *         부품 코드가 없는 원소는 뺀다 — 저장할 때 이미 거르지만, 화면에 이름도 코드도 없는
     *         줄을 내보낼 수는 없다
     */
    public List<UnresolvedPart> read(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            UnresolvedPart[] parsed = objectMapper.readValue(json, UnresolvedPart[].class);
            return parsed == null ? List.of()
                    : Arrays.stream(parsed)
                            .filter(Objects::nonNull)
                            .filter(part -> part.partCode() != null && !part.partCode().isBlank())
                            .toList();
        } catch (RuntimeException e) {
            log.warn("산정하지 못한 부위 목록을 읽지 못해 빈 목록으로 대체한다 (길이 {})", json.length(), e);
            return List.of();
        }
    }
}
