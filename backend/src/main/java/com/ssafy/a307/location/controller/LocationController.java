package com.ssafy.a307.location.controller;

import com.ssafy.a307.common.kakao.KakaoLocalPort;
import com.ssafy.a307.common.response.ApiResponse;
import com.ssafy.a307.location.domain.CategoryGroupCode;
import com.ssafy.a307.location.dto.AddressResponse;
import com.ssafy.a307.location.dto.LocationPageResponse;
import com.ssafy.a307.location.dto.PlaceResponse;
import com.ssafy.a307.location.service.LocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 주소·좌표·장소 조회. 카카오 로컬 REST API 를 이 프로젝트 계약으로 감싼다.
 *
 * <h2>인증이 필요하다</h2>
 * {@code PUBLIC_PATHS} 에 넣지 않았다. {@code SecurityConfig} 의
 * {@code anyRequest().hasAnyRole("USER","ADMIN")} 이 그대로 적용돼 비로그인은 401 이다.
 * <b>쿼터가 걸린 외부 자원을 비인증에 열지 않는다</b> — 열면 누구나 우리 키로 카카오 호출
 * 회수를 태울 수 있고, 그것이 바닥나면 로그인한 사용자의 조회까지 함께 멈춘다.
 *
 * <h2>⚠️ 축 순서 — {@code latitude} 와 {@code longitude}</h2>
 * 이 API 는 {@code x}/{@code y} 라는 이름을 쓰지 않는다. 카카오 REST 는 {@code x}=경도지만,
 * FE 가 응답을 카카오 JS SDK 로 넘길 때는 <b>{@code new kakao.maps.LatLng(latitude, longitude)}
 * 순서</b>다. 두 값을 바꿔 넣으면 한국 좌표가 인도양으로 가는데 HTTP 오류는 나지 않는다.
 * 그래서 서버가 대한민국 범위를 벗어난 좌표를 <b>카카오 호출 전에</b> 400 으로 거절한다
 * ({@code KoreaBounds}).
 *
 * <h2>필수 파라미터를 {@code required = false} 로 둔 이유</h2>
 * 값이 없어도 된다는 뜻이 아니다. {@code LocationService} 가 같은 조건을
 * {@code INVALID_REQUEST} 로 거절하는데, Spring 이 바인드 단계에서 먼저 막으면
 * {@code MissingServletRequestParameterException} 이 나고 그것은 {@code GlobalExceptionHandler}
 * 에 매핑되지 않아 <b>캐치올 {@code Exception} 핸들러로 떨어지며 500 이 된다.</b>
 * 어떤 파라미터가 왜 문제인지도 사라진다. 사용자가 잘못 부른 것을 서버 오류로
 * 보이게 두지 않으려고 검증을 서비스 한 곳으로 통일했다.
 *
 * <h2>페이지는 0-based 다</h2>
 * 카카오는 1-based 지만 이 프로젝트의 다른 조회 API 와 맞춰 <b>0-based</b>로 노출한다.
 * 변환은 {@code LocationService} 와 {@code LocationPageResponse} 두 곳에만 있다.
 *
 * <h2>백엔드가 제공하지 않는 것</h2>
 * 지도 이미지, 로드뷰, 길찾기 경로. 카카오에는 <b>서버용 정적 지도 REST API 가 없고</b>
 * {@code kakao.maps.StaticMap} 은 JS SDK 클래스다. 지도 렌더링과 JavaScript 키·도메인 등록은
 * FE 몫이며, 서버는 JS 키를 보관하지도 응답으로 내보내지도 않는다.
 */
@RestController
@RequestMapping("/api/locations")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService service;

    /**
     * 주소 문자열 → 좌표.
     *
     * @param size  1~30. 넘으면 <b>거절하지 않고 30 으로 절단</b>한다
     * @param exact {@code true} 면 정확한 주소만 찾는다({@code analyze_type=exact}).
     *              기본은 유사 주소까지 포함
     */
    @GetMapping("/geocode")
    public ApiResponse<LocationPageResponse<AddressResponse>> geocode(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false, defaultValue = "false") boolean exact) {

        return ApiResponse.of(service.geocode(query, page, size, exact));
    }

    /**
     * 좌표 → 주소. FE 지도에서 사용자가 클릭·핀 이동으로 고른 지점을 저장 가능한 주소로 바꾼다.
     *
     * <p>결과는 <b>0건 또는 1건</b>이다. 바다나 미등록 구역이면 빈 배열이고 <b>404 가 아니다</b> —
     * 지도에서 아무 곳이나 찍을 수 있으므로 "주소 없음" 은 정상 흐름이다.
     */
    @GetMapping("/reverse-geocode")
    public ApiResponse<List<AddressResponse>> reverseGeocode(
            @RequestParam(required = false) BigDecimal latitude,
            @RequestParam(required = false) BigDecimal longitude) {

        return ApiResponse.of(service.reverseGeocode(latitude, longitude));
    }

    /**
     * 키워드로 장소 검색.
     *
     * <p><b>정비소·카센터를 찾는 경로가 여기다.</b> 카카오 카테고리 그룹 코드 18종에는 자동차
     * 정비 업종이 없어서 카테고리 검색으로는 찾을 수 없다({@link CategoryGroupCode} 참고).
     *
     * @param sort   {@code distance} 를 쓰려면 {@code latitude}·{@code longitude} 가
     *               <b>반드시 함께</b> 와야 한다. 없으면 400 이다
     * @param radius 0~20000 미터. 좌표와 함께만 유효하고, 좌표 없이 오면 400 이다.
     *               20000 초과는 절단, 음수는 400
     * @param size   1~15. 넘으면 15 로 절단
     */
    @GetMapping("/places")
    public ApiResponse<LocationPageResponse<PlaceResponse>> searchPlaces(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) BigDecimal latitude,
            @RequestParam(required = false) BigDecimal longitude,
            @RequestParam(required = false) Integer radius,
            @RequestParam(required = false) KakaoLocalPort.PlaceSort sort,
            @RequestParam(required = false) CategoryGroupCode categoryGroupCode,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        return ApiResponse.of(service.searchPlaces(
                query, latitude, longitude, radius, sort, categoryGroupCode, page, size));
    }

    /**
     * 카테고리 그룹 코드로 주변 장소 검색.
     *
     * @param categoryGroupCode 18종 중 하나. 정의되지 않은 값은 바인딩 단계에서 400 이다 —
     *                          문자열을 그대로 카카오에 넘기지 않는다
     * @param latitude          <b>필수다.</b> 카카오가 중심 좌표 또는 사각 영역을 요구하고,
     *                          이 API 는 중심 좌표만 지원한다
     */
    @GetMapping("/places/category")
    public ApiResponse<LocationPageResponse<PlaceResponse>> searchPlacesByCategory(
            @RequestParam(required = false) CategoryGroupCode categoryGroupCode,
            @RequestParam(required = false) BigDecimal latitude,
            @RequestParam(required = false) BigDecimal longitude,
            @RequestParam(required = false) Integer radius,
            @RequestParam(required = false) KakaoLocalPort.PlaceSort sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        return ApiResponse.of(service.searchPlacesByCategory(
                categoryGroupCode, latitude, longitude, radius, sort, page, size));
    }

    /**
     * 선택 가능한 카테고리 그룹 코드 18종.
     *
     * <p>FE 가 코드와 한글 이름을 하드코딩하지 않게 서버가 준다 — 카카오가 목록을 바꾸면
     * 화면만 조용히 낡는 일을 막는다.
     */
    @GetMapping("/category-groups")
    public ApiResponse<List<CategoryGroupResponse>> categoryGroups() {
        return ApiResponse.of(java.util.Arrays.stream(CategoryGroupCode.values())
                .map(code -> new CategoryGroupResponse(code.code(), code.displayName()))
                .toList());
    }

    /** @param code {@code PM9} 같은 카카오 코드 */
    public record CategoryGroupResponse(String code, String displayName) {
    }
}
