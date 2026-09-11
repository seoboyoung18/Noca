package com.ssafy.a307.location.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.kakao.KakaoLocalException;
import com.ssafy.a307.common.kakao.KakaoLocalPort;
import com.ssafy.a307.location.dto.LocationPageResponse;
import com.ssafy.a307.location.dto.RepairShopResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 주변 정비소 목록의 <b>계약</b>. 스프링 컨텍스트 없이 스텁 포트로 본다.
 *
 * <p>HTTP 상태·인가는 {@code RepairShopApiTest} 가 본다. 여기서 보는 것은 이 facade 가
 * 범용 장소 검색 위에 <b>무엇을 고정하고 무엇을 보장하는가</b>다 — 검색어, 거리순, 중심 좌표,
 * 페이지 안 오름차순, 비어 온 거리의 서버 계산.
 */
@DisplayName("주변 정비소 목록")
class RepairShopSearchServiceTest {

    private static final BigDecimal SEOUL_LAT = new BigDecimal("37.5665");
    private static final BigDecimal SEOUL_LNG = new BigDecimal("126.9780");

    private final StubPort port = new StubPort();
    private final RepairShopSearchService service =
            new RepairShopSearchService(new LocationService(Optional.of(port)));

    // ────────────────────────────────────────────── 서버가 고정하는 것

    @Nested
    @DisplayName("요청 조립 — FE 가 조합하지 않는다")
    class RequestAssembly {

        @Test
        @DisplayName("검색어는 서버가 정한다 — FE 는 검색어를 보내지 않는다")
        void keywordIsEncapsulated() {
            service.searchNearby(SEOUL_LAT, SEOUL_LNG, null, null, null);

            assertThat(port.keywordQuery.query()).isEqualTo(RepairShopSearchService.SEARCH_KEYWORD);
            assertThat(port.keywordQuery.categoryGroup())
                    .as("카카오 카테고리 18종에 정비 업종이 없다 — 없는 코드를 지어내지 않는다")
                    .isNull();
        }

        @Test
        @DisplayName("정렬은 항상 거리순이다")
        void sortIsAlwaysDistance() {
            service.searchNearby(SEOUL_LAT, SEOUL_LNG, null, null, null);

            assertThat(port.keywordQuery.sort()).isEqualTo(KakaoLocalPort.PlaceSort.DISTANCE);
        }

        @Test
        @DisplayName("현재 위치가 곧 중심 좌표다 — 위도·경도가 서로 바뀌지 않는다")
        void currentLocationIsTheCenter() {
            service.searchNearby(SEOUL_LAT, SEOUL_LNG, null, null, null);

            assertThat(port.keywordQuery.center().latitude()).isEqualByComparingTo(SEOUL_LAT);
            assertThat(port.keywordQuery.center().longitude()).isEqualByComparingTo(SEOUL_LNG);
        }

        @Test
        @DisplayName("반경을 안 보내면 반경 없이 거리순이다 — 근거 없는 기본 반경을 만들지 않는다")
        void radiusIsOptional() {
            service.searchNearby(SEOUL_LAT, SEOUL_LNG, null, null, null);

            assertThat(port.keywordQuery.radius()).isNull();
        }

        @ParameterizedTest(name = "radius={0} → {1}")
        @CsvSource({"0, 0", "3000, 3000", "20000, 20000", "50000, 20000"})
        @DisplayName("반경 상한 20000m 초과는 거절하지 않고 자른다")
        void radiusIsClamped(int requested, int expected) {
            service.searchNearby(SEOUL_LAT, SEOUL_LNG, requested, null, null);

            assertThat(port.keywordQuery.radius()).isEqualTo(expected);
        }

        @ParameterizedTest(name = "page={0} → 카카오 page={1}")
        @CsvSource({"0, 1", "2, 3", "999, 45"})
        @DisplayName("page 는 0-based 이고 카카오에는 1-based 로 간다")
        void pageIsZeroBased(int requested, int kakaoPage) {
            service.searchNearby(SEOUL_LAT, SEOUL_LNG, null, requested, null);

            assertThat(port.keywordQuery.page()).isEqualTo(kakaoPage);
        }

        @ParameterizedTest(name = "size={0} → {1}")
        @CsvSource({"1, 1", "15, 15", "100, 15"})
        @DisplayName("size 상한은 15 다 — 카카오 키워드 검색의 상한")
        void sizeIsClamped(int requested, int expected) {
            service.searchNearby(SEOUL_LAT, SEOUL_LNG, null, null, requested);

            assertThat(port.keywordQuery.size()).isEqualTo(expected);
        }
    }

    // ────────────────────────────────────────────── 거절

    @Nested
    @DisplayName("거절 — 카카오를 부르기 전에 끝난다")
    class Rejection {

        @ParameterizedTest(name = "latitude={0}, longitude={1}")
        @CsvSource(value = {"null, null", "37.5665, null", "null, 126.9780"}, nullValues = "null")
        @DisplayName("현재 위치가 없으면 400 이다 — 기준점 없이 가까운 순을 만들 수 없다")
        void locationIsRequired(BigDecimal latitude, BigDecimal longitude) {
            BusinessException e = catchThrowableOfType(BusinessException.class,
                    () -> service.searchNearby(latitude, longitude, null, null, null));

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThat(port.calls).isZero();
        }

        @Test
        @DisplayName("위도·경도를 바꿔 보내면 400 이고 카카오를 부르지 않는다")
        void swappedAxesAreRejected() {
            BusinessException e = catchThrowableOfType(BusinessException.class,
                    () -> service.searchNearby(SEOUL_LNG, SEOUL_LAT, null, null, null));

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThat(e).hasMessageContaining("바꿔");
            assertThat(port.calls).isZero();
        }

        @Test
        @DisplayName("음수 반경은 400 이다")
        void negativeRadiusIsRejected() {
            BusinessException e = catchThrowableOfType(BusinessException.class,
                    () -> service.searchNearby(SEOUL_LAT, SEOUL_LNG, -1, null, null));

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThat(port.calls).isZero();
        }

        @Test
        @DisplayName("키가 없으면 503 이다")
        void unavailableWithoutKey() {
            RepairShopSearchService withoutKey =
                    new RepairShopSearchService(new LocationService(Optional.empty()));

            BusinessException e = catchThrowableOfType(BusinessException.class,
                    () -> withoutKey.searchNearby(SEOUL_LAT, SEOUL_LNG, null, null, null));

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("카카오 쿼터 초과는 429 로 나간다 — 500 으로 뭉개지지 않는다")
        void quotaIsTooManyRequests() {
            port.failure = new KakaoLocalException(ErrorCode.TOO_MANY_REQUESTS, false,
                    "위치 정보 조회 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.");

            BusinessException e = catchThrowableOfType(BusinessException.class,
                    () -> service.searchNearby(SEOUL_LAT, SEOUL_LNG, null, null, null));

            assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
            assertThat(port.calls).as("쿼터 초과를 다시 부르지 않는다").isEqualTo(1);
        }
    }

    // ────────────────────────────────────────────── 서버가 보장하는 것

    @Nested
    @DisplayName("응답 — 거리와 순서")
    class Response {

        @Test
        @DisplayName("카카오가 순서를 어겨도 페이지 안에서는 가까운 순이다")
        void pageIsSortedByDistance() {
            port.places.add(place("far", 900, SEOUL_LAT, SEOUL_LNG));
            port.places.add(place("near", 300, SEOUL_LAT, SEOUL_LNG));
            port.places.add(place("mid", 600, SEOUL_LAT, SEOUL_LNG));

            var page = service.searchNearby(SEOUL_LAT, SEOUL_LNG, null, null, null);

            assertThat(page.content()).extracting(RepairShopResponse::placeId)
                    .containsExactly("near", "mid", "far");
            assertThat(page.content()).extracting(RepairShopResponse::distanceMeters)
                    .containsExactly(300, 600, 900);
        }

        @Test
        @DisplayName("거리가 같으면 카카오가 준 순서를 지킨다 — 정렬이 페이지를 흔들지 않는다")
        void tiesKeepKakaoOrder() {
            port.places.add(place("first", 500, SEOUL_LAT, SEOUL_LNG));
            port.places.add(place("second", 500, SEOUL_LAT, SEOUL_LNG));

            var page = service.searchNearby(SEOUL_LAT, SEOUL_LNG, null, null, null);

            assertThat(page.content()).extracting(RepairShopResponse::placeId)
                    .containsExactly("first", "second");
        }

        @Test
        @DisplayName("카카오 거리가 비어 오면 서버가 현재 위치로부터 직선거리를 계산한다")
        void missingDistanceIsComputed() {
            // 위도 0.01° ≈ 1,112m (경도가 같을 때)
            port.places.add(place("computed", null, new BigDecimal("37.5765"), SEOUL_LNG));

            var shop = service.searchNearby(SEOUL_LAT, SEOUL_LNG, null, null, null).content().get(0);

            assertThat(shop.distanceMeters()).isBetween(1_105, 1_120);
        }

        @Test
        @DisplayName("좌표도 거리도 없는 장소는 맨 뒤이고 distanceMeters 는 null 이다")
        void unknownDistanceGoesLast() {
            port.places.add(place("unknown", null, null, null));
            port.places.add(place("known", 800, SEOUL_LAT, SEOUL_LNG));

            var page = service.searchNearby(SEOUL_LAT, SEOUL_LNG, null, null, null);

            assertThat(page.content()).extracting(RepairShopResponse::placeId)
                    .containsExactly("known", "unknown");
            assertThat(page.content().get(1).distanceMeters()).isNull();
        }

        @Test
        @DisplayName("빈 결과도 정상 응답이다")
        void emptyResultIsFine() {
            LocationPageResponse<RepairShopResponse> page =
                    service.searchNearby(SEOUL_LAT, SEOUL_LNG, null, null, null);

            assertThat(page.content()).isEmpty();
            assertThat(page.hasNext()).isFalse();
            assertThat(page.page()).isZero();
        }

        @Test
        @DisplayName("전화번호·도로명 주소는 없으면 null 로 나간다")
        void nullableFieldsStayNull() {
            port.places.add(new KakaoLocalPort.Place("p1", "김씨카센터", "자동차 > 자동차정비", null,
                    null, "서울 중구 태평로1가 31", null,
                    new KakaoLocalPort.Coordinate(SEOUL_LAT, SEOUL_LNG),
                    "http://place.map.kakao.com/p1", 120));

            RepairShopResponse shop = service.searchNearby(SEOUL_LAT, SEOUL_LNG, null, null, null)
                    .content().get(0);

            assertThat(shop.phone()).isNull();
            assertThat(shop.roadAddressName()).isNull();
            assertThat(shop.placeName()).isEqualTo("김씨카센터");
            assertThat(shop.latitude()).isEqualByComparingTo(SEOUL_LAT);
            assertThat(shop.longitude()).isEqualByComparingTo(SEOUL_LNG);
        }

        @Test
        @DisplayName("페이지 메타는 범용 장소 검색과 같은 계약이다")
        void pageMetaFollowsLocationContract() {
            port.places.add(place("a", 100, SEOUL_LAT, SEOUL_LNG));
            port.total = 120;
            port.pageable = 45;
            port.end = false;

            var page = service.searchNearby(SEOUL_LAT, SEOUL_LNG, null, 1, 15);

            assertThat(page.page()).isEqualTo(1);
            assertThat(page.totalElements()).isEqualTo(120);
            assertThat(page.pageableCount()).isEqualTo(45);
            assertThat(page.totalPages()).isEqualTo(3);
            assertThat(page.hasNext()).isTrue();
        }
    }

    // ────────────────────────────────────────────── 스텁

    private static KakaoLocalPort.Place place(String id, Integer distance,
                                              BigDecimal latitude, BigDecimal longitude) {
        KakaoLocalPort.Coordinate coordinate = latitude == null
                ? null
                : new KakaoLocalPort.Coordinate(latitude, longitude);
        return new KakaoLocalPort.Place(id, "정비소-" + id, "자동차 > 자동차정비", null,
                "02-000-0000", "서울 어딘가", "서울 어딘가로 1", coordinate,
                "http://place.map.kakao.com/" + id, distance);
    }

    private static final class StubPort implements KakaoLocalPort {

        private KeywordQuery keywordQuery;
        private int calls;
        private final List<Place> places = new ArrayList<>();
        private int total;
        private int pageable;
        private boolean end = true;
        private KakaoLocalException failure;

        @Override
        public Paged<Address> searchAddress(AddressQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Address> reverseGeocode(Coordinate point) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Paged<Place> searchPlacesByKeyword(KeywordQuery query) {
            calls++;
            keywordQuery = query;
            if (failure != null) throw failure;
            return new Paged<>(places, query.page(), query.size(),
                    Math.max(total, places.size()), Math.max(pageable, places.size()), end);
        }

        @Override
        public Paged<Place> searchPlacesByCategory(CategoryQuery query) {
            throw new UnsupportedOperationException();
        }
    }
}
