package com.ssafy.a307.location.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.kakao.KakaoLocalPort;
import com.ssafy.a307.location.domain.GeoDistance;
import com.ssafy.a307.location.domain.KoreaBounds;
import com.ssafy.a307.location.dto.LocationPageResponse;
import com.ssafy.a307.location.dto.PlaceResponse;
import com.ssafy.a307.location.dto.RepairShopResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 현재 위치 기준 <b>가까운 순</b> 정비소 목록.
 *
 * <h2>범용 장소 검색 위에 얹은 facade 다 — 새 연동이 아니다</h2>
 * 카카오 호출·파라미터 검증·0/1-based 변환·오류 번역은 전부 {@link LocationService} 를 그대로
 * 거친다. 여기서 더하는 것은 <b>FE 가 매번 조합하던 것을 서버가 고정</b>하는 일뿐이다.
 *
 * <table border="1">
 *   <caption>범용 {@code /api/locations/places} 와 무엇이 다른가</caption>
 *   <tr><th>　</th><th>범용 장소 검색</th><th>정비소 목록</th></tr>
 *   <tr><td>검색어</td><td>FE 가 보냄</td><td><b>서버가 {@value #SEARCH_KEYWORD} 로 고정</b></td></tr>
 *   <tr><td>정렬</td><td>FE 선택(기본 정확도순)</td><td><b>항상 거리순</b></td></tr>
 *   <tr><td>현재 위치</td><td>선택</td><td><b>필수</b> — 없으면 400</td></tr>
 *   <tr><td>거리</td><td>좌표를 줬을 때만</td><td><b>항상</b> — 비어 오면 서버가 계산</td></tr>
 *   <tr><td>순서</td><td>카카오 그대로</td><td><b>페이지 안에서 오름차순 보장</b></td></tr>
 * </table>
 *
 * <h2>카테고리 검색을 쓰지 않는 이유</h2>
 * 카카오 카테고리 그룹 18종에 자동차 정비 업종이 없다({@code CategoryGroupCode}). 없는 코드를
 * 지어내지 않고 키워드 검색을 쓴다. 그 검색어가 FE 로 새지 않게 여기 가둔다 — 검색어를 바꿀 일이
 * 생기면 서버 한 곳만 고치면 된다.
 *
 * <h2>"가까운 순" 이 보장하는 범위</h2>
 * 페이지 사이의 순서는 카카오 {@code sort=distance} 가 정하고, <b>한 페이지 안의 오름차순은 이
 * 서비스가 다시 정렬해 보장</b>한다. 거리가 같으면 카카오가 준 순서를 지킨다(안정 정렬) —
 * 페이지를 넘길 때마다 같은 거리의 두 행이 자리를 바꾸지 않게.
 */
@Service
@RequiredArgsConstructor
public class RepairShopSearchService {

    /**
     * 카카오 키워드 검색에 보내는 검색어. <b>FE 는 이 값을 모른다.</b>
     *
     * <p>카카오 장소 분류의 "자동차정비" 표기에 맞췄다. 공백을 넣은 {@code 자동차 정비} 나
     * {@code 정비소} 보다 좁게 잡은 것은 {@code 보일러 정비}·{@code 자전거 정비} 같은 다른 업종이
     * 섞이는 것을 줄이려는 의도다.
     *
     * <p><b>⚠️ 실제 카카오 응답으로 검증하지 못했다</b> — 이 저장소에는 REST API 키가 없다.
     * 키를 발급받으면 서울 몇 지점에서 결과의 {@code categoryName} 을 확인하고 필요하면 이 값만 바꾼다.
     */
    public static final String SEARCH_KEYWORD = "자동차정비";

    /** 거리 오름차순. 거리를 모르는 행은 맨 뒤다. {@link List#sort} 는 안정 정렬이다. */
    private static final Comparator<RepairShopResponse> NEAREST_FIRST = Comparator.comparing(
            RepairShopResponse::distanceMeters, Comparator.nullsLast(Comparator.naturalOrder()));

    private final LocationService locationService;

    /**
     * @param radius 선택. 미터, 0~20000 이고 넘으면 20000 으로 자른다. <b>생략하면 반경 없이
     *               거리순</b>이다 — 근거 없는 기본 반경을 만들지 않았다. 카카오가 노출하는 최대
     *               45건이 곧 가장 가까운 45곳이다
     * @param page   0-based
     * @param size   1~15. 넘으면 15
     */
    public LocationPageResponse<RepairShopResponse> searchNearby(
            BigDecimal latitude, BigDecimal longitude, Integer radius, Integer page, Integer size) {

        KakaoLocalPort.Coordinate here = currentLocation(latitude, longitude);
        LocationPageResponse<PlaceResponse> places = locationService.searchPlaces(
                SEARCH_KEYWORD, here.latitude(), here.longitude(), radius,
                KakaoLocalPort.PlaceSort.DISTANCE, null, page, size);

        List<RepairShopResponse> shops = new ArrayList<>(places.content().size());
        for (PlaceResponse place : places.content()) {
            shops.add(RepairShopResponse.of(place, distanceFrom(here, place)));
        }
        shops.sort(NEAREST_FIRST);

        return new LocationPageResponse<>(shops, places.page(), places.size(),
                places.totalElements(), places.pageableCount(), places.totalPages(), places.hasNext());
    }

    /**
     * 현재 위치는 필수다. 기준점이 없으면 "가까운 순" 이 성립하지 않는다.
     * <p>
     * 대한민국 범위 검증도 여기서 먼저 한다 — 뒤바뀐 위도·경도는 카카오를 부르기 전에 400 이다.
     */
    private static KakaoLocalPort.Coordinate currentLocation(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null || longitude == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "현재 위치(latitude·longitude)가 필요합니다. 정비소 목록은 가까운 순으로만 제공합니다.");
        }
        return KoreaBounds.require(latitude, longitude);
    }

    /** 카카오 값을 먼저 쓴다. 이유는 {@link GeoDistance} 참고. */
    private static Integer distanceFrom(KakaoLocalPort.Coordinate here, PlaceResponse place) {
        if (place.distanceMeters() != null) return place.distanceMeters();
        if (place.latitude() == null || place.longitude() == null) return null;
        return GeoDistance.meters(here, new KakaoLocalPort.Coordinate(place.latitude(), place.longitude()));
    }
}
