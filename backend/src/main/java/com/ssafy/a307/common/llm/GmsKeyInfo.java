package com.ssafy.a307.common.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * {@code GET {base}/key-info} 응답.
 *
 * <p><b>{@code expiredDate} 를 {@code String} 으로 받는다.</b> 문서가 KST 라고만 적고 정확한
 * 형식을 확정하지 않았다. 타입을 {@code LocalDate} 나 {@code Instant} 로 못 박으면 형식이
 * 다를 때 <b>역직렬화가 통째로 실패해 크레딧 조회 자체가 죽는다.</b> 문자열로 받아 두고
 * 만료 판정만 별도로 시도하는 편이 안전하다({@link GmsCreditGuard}).
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 인 이유도 같다 — GMS 가 필드를
 * 늘려도 조회가 깨지지 않아야 한다.
 *
 * <p><b>이 값은 팀 내부 정보다.</b> 사용자 응답·actuator health 에 담지 않는다. 로그에만 남긴다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GmsKeyInfo(
        Long totalCredit,
        Long usedCredit,
        Long remainCredit,
        String expiredDate) {

    public boolean hasRemainCredit() {
        return remainCredit != null;
    }
}
