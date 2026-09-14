package com.ssafy.a307.estimate.domain;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code estimate_item.ref_condition} 의 JSON 을 {@link RefCondition} 으로 읽는다.
 *
 * <p><b>읽지 못해도 예외를 던지지 않는다.</b> 근거 한 줄이 깨졌다고 견적 조회 전체를 500 으로
 * 끊으면, 사용자는 금액도 항목도 못 본다. 근거가 없다는 사실을 화면에 알리는 편이 낫다 —
 * 요구사항 51행이 "근거가 부족한 항목은 그 사실이 명시된다"고 한 것과 같은 방향이고,
 * {@link RepairMethodDisplay#displayNameOf} 가 모르는 코드를 원문으로 흘리는 것과 같은 판단이다.
 *
 * <p><b>원문을 로그에 남기지 않는다.</b> 이 JSON 에는 그 사용자의 수리비 통계가 들어 있고,
 * 로그는 화면보다 오래 남는다. 길이만 남긴다.
 *
 * <p><b>모르는 필드는 무시한다.</b> AI 연동 계약이 아직 "협의 후 확정" 상태라 근거 필드가
 * 늘 수 있는데, 그때 먼저 저장된 행을 읽지 못하면 과거 견적의 근거가 통째로 사라진다.
 * 실제로 2026-09-12 2차 수정본이 항목 근거에 필드를 더했다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefConditionReader {

    private final ObjectMapper objectMapper;

    /**
     * @param json {@code ref_condition} 원문. 컬럼이 {@code NOT NULL} 이라 정상 경로에서는
     *             {@code null} 이 오지 않지만, 근거가 없는 행은 {@code '{}'} 로 저장된다
     * @return 읽은 근거. 비었거나 읽지 못하면 {@link RefCondition#EMPTY}
     */
    public RefCondition read(String json) {
        if (json == null || json.isBlank()) {
            return RefCondition.EMPTY;
        }
        try {
            RefCondition parsed = objectMapper.readValue(json, RefCondition.class);
            return parsed == null ? RefCondition.EMPTY : parsed;
        } catch (RuntimeException e) {
            log.warn("산정 근거를 읽지 못해 빈 근거로 대체한다 (길이 {})", json.length(), e);
            return RefCondition.EMPTY;
        }
    }
}
