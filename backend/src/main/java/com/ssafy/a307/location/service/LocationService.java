package com.ssafy.a307.location.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.kakao.KakaoLocalPort;
import com.ssafy.a307.location.domain.CategoryGroupCode;
import com.ssafy.a307.location.domain.KoreaBounds;
import com.ssafy.a307.location.dto.AddressResponse;
import com.ssafy.a307.location.dto.LocationPageResponse;
import com.ssafy.a307.location.dto.PlaceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 주소·좌표·장소 조회. <b>외부 조회 프록시라 DB 를 쓰지 않는다.</b>
 *
 * <h2>키가 없으면 503 이다</h2>
 * {@code Optional<KakaoLocalPort>} 로 받는다. 키가 설정되지 않으면 전송 계층 빈이 뜨지 않고
 * ({@code KakaoKeyPresentCondition}), 이 서비스는 503 을 낸다. <b>애플리케이션 기동은
 * 성공한다</b> — 지도 키가 없다고 차량·사고 API 까지 죽으면 안 된다.
 * {@code EstimateFileValidationService.storage()} 와 같은 관례다.
 *
 * <h2>파라미터 정책 — 절단하고, 조합만 거절한다</h2>
 * {@code page}·{@code size} 상한 초과는 <b>거절하지 않고 상한으로 절단</b>한다
 * (S15P21A307-225 정책). 반면 <b>조합이 성립하지 않는 요청은 거절</b>한다 —
 * {@code sort=distance} 인데 좌표가 없거나, 카테고리 검색에 좌표도 {@code rect} 도 없는 경우다.
 * 절단은 "덜 주는" 것이지만 조합 오류는 <b>사용자가 기대한 것과 다른 결과</b>를 주기 때문이다.
 * 거절은 전부 카카오를 호출하기 <b>전에</b> 끝난다 — 쿼터를 태우지 않는다.
 *
 * <h2>캐시를 넣지 않았다</h2>
 * 이 저장소에 Redis 가 있어 역지오코딩 캐시를 붙일 수는 있다. 넣지 않은 이유는
 * <b>아직 이 API 를 쓰는 화면이 없다</b>는 것이다. 호출 패턴(지도 이동 빈도·중복률)을 모르는
 * 상태에서 TTL 과 좌표 반올림 정밀도를 정하면 근거 없는 숫자가 굳고, 조회 경로에 Redis 장애가
 * 전파되는 위험만 먼저 생긴다. 대신 FE 인수인계 문서에 <b>디바운스·최소 이동거리 권고</b>를
 * 적었고, 실제 소비처가 붙은 뒤 호출량을 보고 결정할 항목으로 남겼다.
 */
@Service
@RequiredArgsConstructor
public class LocationService {

    /** 카카오 문서 기준 상한. 넘으면 카카오가 {@code -2} 로 거절하므로 우리가 먼저 자른다. */
    private static final int MAX_PAGE = 45;
    private static final int MAX_ADDRESS_SIZE = 30;
    private static final int MAX_PLACE_SIZE = 15;
    private static final int DEFAULT_ADDRESS_SIZE = 10;
    private static final int DEFAULT_PLACE_SIZE = 15;
    private static final int MAX_RADIUS_METERS = 20_000;

    private final Optional<KakaoLocalPort> kakaoLocalPort;

    // ── 주소 → 좌표 ───────────────────────────────────────────────────────

    public LocationPageResponse<AddressResponse> geocode(
            String query, Integer page, Integer size, boolean exact) {

        requireQuery(query);
        var request = new KakaoLocalPort.AddressQuery(
                query, kakaoPage(page), size(size, DEFAULT_ADDRESS_SIZE, MAX_ADDRESS_SIZE), exact);
        return LocationPageResponse.of(port().searchAddress(request), AddressResponse::from);
    }

    // ── 좌표 → 주소 ───────────────────────────────────────────────────────

    /**
     * @return 카카오 {@code total_count} 가 0 이면 <b>빈 목록</b>이다. 404 로 만들지 않는다 —
     *         바다나 미등록 구역을 고른 것은 오류가 아니고, FE 는 "주소를 찾을 수 없습니다" 를
     *         정상 화면으로 그리면 된다
     */
    public List<AddressResponse> reverseGeocode(BigDecimal latitude, BigDecimal longitude) {
        var point = KoreaBounds.require(latitude, longitude);
        return port().reverseGeocode(point)
                .map(AddressResponse::from)
                .map(List::of)
                .orElseGet(List::of);
    }

    // ── 키워드 장소 검색 ──────────────────────────────────────────────────

    public LocationPageResponse<PlaceResponse> searchPlaces(
            String query, BigDecimal latitude, BigDecimal longitude, Integer radius,
            KakaoLocalPort.PlaceSort sort, CategoryGroupCode categoryGroupCode,
            Integer page, Integer size) {

        requireQuery(query);
        KakaoLocalPort.Coordinate center = optionalCenter(latitude, longitude);
        requireCenterForDistanceSort(sort, center);
        requireCenterForRadius(radius, center);

        var request = new KakaoLocalPort.KeywordQuery(
                query, center, radius(radius), sort, categoryGroupCode,
                kakaoPage(page), size(size, DEFAULT_PLACE_SIZE, MAX_PLACE_SIZE));
        return LocationPageResponse.of(port().searchPlacesByKeyword(request), PlaceResponse::from);
    }

    // ── 카테고리 장소 검색 ────────────────────────────────────────────────

    public LocationPageResponse<PlaceResponse> searchPlacesByCategory(
            CategoryGroupCode categoryGroupCode, BigDecimal latitude, BigDecimal longitude,
            Integer radius, KakaoLocalPort.PlaceSort sort, Integer page, Integer size) {

        if (categoryGroupCode == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "categoryGroupCode 는 필수입니다.");
        }
        KakaoLocalPort.Coordinate center = optionalCenter(latitude, longitude);
        if (center == null) {
            // 카카오는 (x,y,radius) 또는 rect 중 하나를 반드시 요구한다. 없으면 -2 로 거절하는데
            // 그것은 쿼터를 태우고 FE 에는 뭉뚱그린 메시지만 남는다. 여기서 끊는다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "카테고리 검색에는 중심 좌표(latitude·longitude)가 필요합니다.");
        }
        requireCenterForDistanceSort(sort, center);

        var request = new KakaoLocalPort.CategoryQuery(
                categoryGroupCode, center, radius(radius), null, sort,
                kakaoPage(page), size(size, DEFAULT_PLACE_SIZE, MAX_PLACE_SIZE));
        return LocationPageResponse.of(port().searchPlacesByCategory(request), PlaceResponse::from);
    }

    // ── 검증·변환 ─────────────────────────────────────────────────────────

    /**
     * 키가 없으면 여기서 503 이 난다.
     *
     * <p>메시지에 "관리자에게 문의" 를 넣은 것은 사용자가 할 수 있는 일이 없기 때문이다.
     * 키 설정은 운영 작업이고, 기동 로그에 이미 비활성 사유가 남아 있다.
     */
    private KakaoLocalPort port() {
        return kakaoLocalPort.orElseThrow(() -> new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE,
                "위치 정보 서비스가 설정되지 않았습니다. 관리자에게 문의해 주세요."));
    }

    private static void requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "query 는 필수입니다.");
        }
    }

    /** 좌표는 선택이지만 <b>둘 중 하나만</b> 오면 실수다 — 조용히 무시하지 않고 거절한다. */
    private static KakaoLocalPort.Coordinate optionalCenter(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null && longitude == null) return null;
        if (latitude == null || longitude == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "latitude 와 longitude 는 함께 보내야 합니다.");
        }
        return KoreaBounds.require(latitude, longitude);
    }

    /**
     * {@code sort=distance} 는 중심 좌표가 있어야 의미가 있다.
     * <p>
     * 좌표 없이 거리순을 요청하면 카카오는 오류 대신 <b>정확도순 결과를 그냥 돌려준다.</b>
     * 사용자는 "가까운 순" 을 눌렀는데 아무 순서로 정렬된 목록을 보게 되므로 거절한다.
     */
    private static void requireCenterForDistanceSort(KakaoLocalPort.PlaceSort sort,
                                                     KakaoLocalPort.Coordinate center) {
        if (sort == KakaoLocalPort.PlaceSort.DISTANCE && center == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "거리순 정렬에는 중심 좌표(latitude·longitude)가 필요합니다.");
        }
    }

    /** {@code radius} 만 오면 반경의 기준점이 없다. 조용히 무시하면 넓은 결과가 나와 오해를 만든다. */
    private static void requireCenterForRadius(Integer radius, KakaoLocalPort.Coordinate center) {
        if (radius != null && center == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "radius 는 중심 좌표(latitude·longitude)와 함께 보내야 합니다.");
        }
    }

    /**
     * 반경. <b>음수는 거절하고 상한은 절단한다.</b>
     *
     * <p>둘을 다르게 다루는 이유는 의도 해석이다. {@code 30000} 은 "가능한 만큼 넓게" 로 읽을
     * 수 있어 20km 로 자르면 사용자 기대에 가깝다. 음수는 어떤 의도로도 읽히지 않는 오류다.
     */
    private static Integer radius(Integer radius) {
        if (radius == null) return null;
        if (radius < 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "radius 는 0 이상이어야 합니다.");
        }
        return Math.min(radius, MAX_RADIUS_METERS);
    }

    /**
     * 0-based 요청 페이지를 카카오의 1-based 로 바꾼다. <b>변환은 여기 한 곳에서만 한다.</b>
     *
     * <p>상한 초과는 절단이다. {@code page=999} 를 400 으로 거절하면 목록 끝에서 스크롤이
     * 튀는 화면이 되고, 카카오도 45 초과를 {@code -2} 로 거절하므로 어느 쪽이든 결과는 없다.
     */
    private static int kakaoPage(Integer page) {
        int zeroBased = page == null || page < 0 ? 0 : page;
        return Math.min(zeroBased + 1, MAX_PAGE);
    }

    private static int size(Integer size, int defaultSize, int maxSize) {
        if (size == null || size < 1) return defaultSize;
        return Math.min(size, maxSize);
    }

}
