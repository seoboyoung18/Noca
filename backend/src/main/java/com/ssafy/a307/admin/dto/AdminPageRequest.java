package com.ssafy.a307.admin.dto;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * 관리자 목록의 페이지·정렬 파라미터를 한 곳에서 해석한다.
 *
 * <p><b>정렬 필드를 화이트리스트로 받는다.</b> 클라이언트 문자열을 그대로
 * {@code Sort.by} 에 넘기면 엔티티에 없는 속성으로 500 이 나고, 매핑되지 않은 컬럼 이름이
 * 노출된다. 목록마다 허용 필드를 정해 두고 벗어나면 기본 정렬로 되돌린다.
 *
 * <p><b>페이지 크기에 상한이 있다.</b> 부품명 매핑은 1만 5천 건이라 {@code size=100000} 한 번에
 * 서버 메모리와 응답이 함께 무너진다.
 */
public final class AdminPageRequest {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 200;

    private AdminPageRequest() {
    }

    public static Pageable of(Integer page, Integer size, String sort, Sort defaultSort,
                              Set<String> sortable) {
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return PageRequest.of(safePage, safeSize, resolveSort(sort, defaultSort, sortable));
    }

    /** {@code "displayOrder,desc"} 형태. 알 수 없는 필드는 조용히 기본 정렬로 되돌린다. */
    private static Sort resolveSort(String sort, Sort defaultSort, Set<String> sortable) {
        if (sort == null || sort.isBlank()) return defaultSort;
        String[] parts = sort.split(",", 2);
        String property = parts[0].strip();
        if (!sortable.contains(property)) return defaultSort;
        boolean descending = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].strip());
        return Sort.by(descending ? Sort.Direction.DESC : Sort.Direction.ASC, property);
    }
}
