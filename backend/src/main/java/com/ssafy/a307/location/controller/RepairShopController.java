package com.ssafy.a307.location.controller;

import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.location.dto.LocationPageResponse;
import com.ssafy.a307.location.dto.RepairShopResponse;
import com.ssafy.a307.location.service.RepairShopSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 현재 위치 기준 가까운 순 정비소 목록.
 *
 * <p><b>FE 는 검색어·정렬을 보내지 않는다.</b> 좌표만 보내면 서버가 정비소 검색어와 거리순을
 * 고정한다. 범용 장소 검색({@code /api/locations/places})과의 차이는
 * {@link RepairShopSearchService} Javadoc 에 있다.
 *
 * <h2>인증이 필요하다</h2>
 * {@code PUBLIC_PATHS} 에 넣지 않았다. 카카오 쿼터가 걸린 자원이라 비로그인은 401 이다.
 *
 * <h2>파라미터를 {@code required = false} 로 둔 이유</h2>
 * {@code LocationController} 와 같다 — 필수 검사를 서비스가 {@code INVALID_REQUEST} 로 한다.
 * 스프링이 먼저 막으면 {@code MissingServletRequestParameterException} 이 catch-all 로 떨어져
 * 500 이 된다.
 */
@RestController
@RequestMapping("/api/repair-shops")
@RequiredArgsConstructor
public class RepairShopController {

    private final RepairShopSearchService service;

    /**
     * @param latitude  <b>필수.</b> 현재 위치 위도. 없으면 400
     * @param longitude <b>필수.</b> 현재 위치 경도. 없으면 400
     * @param radius    선택. 미터 0~20000, 초과는 절단, 음수는 400. 생략하면 반경 없이 거리순
     * @param page      0-based. 45페이지를 넘으면 절단
     * @param size      1~15, 초과는 절단
     */
    @GetMapping
    public ApiResponse<LocationPageResponse<RepairShopResponse>> searchNearby(
            @RequestParam(required = false) BigDecimal latitude,
            @RequestParam(required = false) BigDecimal longitude,
            @RequestParam(required = false) Integer radius,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        return ApiResponse.of(service.searchNearby(latitude, longitude, radius, page, size));
    }
}
