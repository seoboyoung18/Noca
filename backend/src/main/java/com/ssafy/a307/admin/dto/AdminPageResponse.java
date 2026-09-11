package com.ssafy.a307.admin.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * 관리자 목록 공통 봉투.
 *
 * <p>기존 목록 응답이 {@code accidents}·{@code content}·{@code vehicles} 로 제각각이고
 * {@code hasNext} 유무까지 갈려 FE 가 API 마다 어댑터를 만들고 있다. 관리자 API 는 처음부터
 * <b>하나로 통일</b>한다 — 새로 늘어나는 화면에서 같은 문제를 반복하지 않기 위해서다.
 */
public record AdminPageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext) {

    public static <E, T> AdminPageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new AdminPageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.hasNext());
    }
}
