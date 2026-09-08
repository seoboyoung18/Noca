package com.ssafy.a307.accident.dto;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * 사고 이력 목록의 페이지 응답.
 * <p>
 * Spring 의 {@code Page} 를 그대로 직렬화하지 않는다 — 그 형태는 버전에 따라 바뀌고
 * (Boot 3.3 부터 직렬화 경고가 붙는다) 공통 규약이 요구하는 {@code data} 봉투와도 어긋난다.
 * 필요한 필드만 골라 계약으로 고정한다.
 * <p>
 * 항목 이름을 {@code accidents} 로 두어 기존 {@link AccidentListResponse} 와 같은 키를 쓴다.
 * FE 인수인계 문서가 이미 그 키를 계약으로 적었으므로 바꾸지 않는다.
 */
public record AccidentPageResponse(
        List<AccidentResponse> accidents,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {

    public static AccidentPageResponse from(Page<AccidentResponse> page) {
        return new AccidentPageResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext()
        );
    }
}
