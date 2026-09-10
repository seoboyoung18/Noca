package com.ssafy.a307.location.dto;

import com.ssafy.a307.common.kakao.KakaoLocalPort;

import java.util.List;
import java.util.function.Function;

/**
 * 위치 조회 목록 한 페이지. <b>이 프로젝트의 기존 페이지 계약을 따른다.</b>
 *
 * <h2>0-based 로 통일한다 — 카카오는 1-based 다</h2>
 * 카카오 로컬은 {@code page=1} 이 첫 페이지다. 이 프로젝트의 다른 모든 조회 API
 * ({@code AdminPageResponse}, 사고 조회)는 {@code page=0} 이 첫 페이지다.
 *
 * <p><b>외부 계약은 0-based 로 고른다.</b> 이유는 하나다 — FE 가 화면마다 다른 규약을 기억하게
 * 만들면 언젠가 한 페이지가 밀린 목록을 보게 되고, 그 버그는 <b>2페이지부터만</b> 나타나서
 * 찾기 어렵다. 서버가 한 곳에서 {@code +1} 을 하는 편이 낫다. 변환은
 * {@code LocationService} 의 요청 조립 지점과 이 클래스의 {@link #of} 두 곳뿐이다.
 *
 * @param page          0-based 페이지 번호
 * @param totalElements 카카오 {@code meta.total_count} — 검색된 전체 문서 수
 * @param pageableCount 카카오 {@code meta.pageable_count} — <b>노출 가능한 문서 수.
 *                      장소 검색은 최대 45로 잘린다.</b> {@code totalElements} 가 1000 이어도
 *                      이 값이 45 면 46번째 문서는 어떤 페이지로도 볼 수 없다.
 *                      두 값의 의미가 달라 하나로 합치지 않았다
 * @param totalPages    <b>{@code pageableCount} 기준</b>이다. {@code totalElements} 로 계산하면
 *                      실제로 열 수 없는 페이지 번호를 FE 에 알려주게 된다
 * @param hasNext       카카오 {@code is_end} 의 반대. 다음 페이지가 있는가
 */
public record LocationPageResponse<T>(
        List<T> content,
        int page,
        int size,
        int totalElements,
        int pageableCount,
        int totalPages,
        boolean hasNext) {

    public static <S, T> LocationPageResponse<T> of(KakaoLocalPort.Paged<S> paged,
                                                    Function<S, T> mapper) {
        return new LocationPageResponse<>(
                paged.items().stream().map(mapper).toList(),
                // 카카오 1-based → 프로젝트 0-based
                Math.max(0, paged.page() - 1),
                paged.size(),
                paged.totalCount(),
                paged.pageableCount(),
                totalPages(paged.pageableCount(), paged.size()),
                !paged.end());
    }

    private static int totalPages(int pageableCount, int size) {
        if (size < 1) return 0;
        return (pageableCount + size - 1) / size;
    }
}
