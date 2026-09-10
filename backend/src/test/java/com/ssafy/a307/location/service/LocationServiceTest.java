package com.ssafy.a307.location.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.kakao.KakaoLocalPort;
import com.ssafy.a307.location.domain.CategoryGroupCode;
import com.ssafy.a307.location.dto.AddressResponse;
import com.ssafy.a307.location.dto.LocationPageResponse;
import com.ssafy.a307.location.dto.PlaceResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 위치 서비스의 <b>변환 규약</b>. 스프링 컨텍스트 없이 스텁 포트로 본다.
 *
 * <p>{@code LocationApiTest} 는 키가 없는 상태(포트 부재)를 보고, {@code KakaoLocalClientTest} 는
 * 카카오 응답 해석을 본다. 여기서 보는 것은 그 사이 — <b>포트가 있을 때 요청 파라미터가 어떻게
 * 조립되고 응답이 어떤 페이지 계약으로 나가는가</b>다. 세 계층을 한 테스트로 묶으면 어디가
 * 깨졌는지 알 수 없다.
 */
@DisplayName("위치 서비스 변환")
class LocationServiceTest {

    // ────────────────────────────────────────────── 페이지 변환

    @Nested
    @DisplayName("페이지 규약 — 외부는 0-based, 카카오는 1-based")
    class Paging {

        /**
         * <b>이 변환이 틀리면 2페이지부터만 어긋난다.</b> 첫 페이지는 0→1 이든 0→0 이든
         * 그럴듯한 결과가 나와서, 실수를 발견하는 시점이 늦다.
         */
        @ParameterizedTest(name = "요청 page={0} → 카카오 page={1}")
        @CsvSource({"0, 1", "1, 2", "5, 6", "44, 45", "45, 45", "999, 45"})
        @DisplayName("요청 page 에 1 을 더해 카카오로 보내고, 45 를 넘지 않게 자른다")
        void requestPageIsConvertedAndClamped(Integer requested, int expectedKakaoPage) {
            StubPort port = new StubPort();
            new LocationService(Optional.of(port)).geocode("서울시청", requested, 10, false);

            assertThat(port.addressQuery.page()).isEqualTo(expectedKakaoPage);
        }

        @Test
        @DisplayName("page 를 안 보내거나 음수면 첫 페이지다")
        void missingOrNegativePageIsFirst() {
            StubPort port = new StubPort();
            LocationService service = new LocationService(Optional.of(port));

            service.geocode("서울시청", null, 10, false);
            assertThat(port.addressQuery.page()).isEqualTo(1);

            service.geocode("서울시청", -3, 10, false);
            assertThat(port.addressQuery.page()).isEqualTo(1);
        }

        @Test
        @DisplayName("응답의 카카오 page 는 0-based 로 되돌린다")
        void responsePageIsZeroBased() {
            StubPort port = new StubPort();
            port.addressResult = new KakaoLocalPort.Paged<>(
                    List.of(address()), 3, 10, 100, 45, false);

            var response = new LocationService(Optional.of(port))
                    .geocode("서울시청", 2, 10, false);

            assertThat(response.page())
                    .as("카카오가 3페이지라고 답했으면 FE 에는 2 로 나간다")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("hasNext 는 카카오 is_end 의 반대다")
        void hasNextIsInverseOfIsEnd() {
            StubPort port = new StubPort();

            port.addressResult = new KakaoLocalPort.Paged<>(List.of(address()), 1, 10, 100, 45, true);
            assertThat(new LocationService(Optional.of(port)).geocode("x", 0, 10, false).hasNext())
                    .isFalse();

            port.addressResult = new KakaoLocalPort.Paged<>(List.of(address()), 1, 10, 100, 45, false);
            assertThat(new LocationService(Optional.of(port)).geocode("x", 0, 10, false).hasNext())
                    .isTrue();
        }

        /**
         * 카카오 장소 검색의 {@code pageable_count} 는 <b>최대 45로 잘린다.</b>
         * {@code total_count} 로 쪽수를 계산하면 <b>열 수 없는 페이지 번호</b>를 FE 에 알려주게 된다.
         */
        @Test
        @DisplayName("totalPages 는 pageableCount 기준이다 — totalCount 로 계산하면 열 수 없는 쪽이 생긴다")
        void totalPagesUsesPageableCount() {
            StubPort port = new StubPort();
            port.addressResult = new KakaoLocalPort.Paged<>(
                    List.of(address()), 1, 15, 1_000, 45, false);

            var response = new LocationService(Optional.of(port)).geocode("x", 0, 15, false);

            assertThat(response.totalElements()).as("검색된 전체 문서 수").isEqualTo(1_000);
            assertThat(response.pageableCount()).as("노출 가능한 문서 수 (최대 45)").isEqualTo(45);
            assertThat(response.totalPages())
                    .as("45건을 15개씩 = 3쪽. 1000/15=67쪽이라고 하면 4쪽부터 빈 결과다")
                    .isEqualTo(3);
        }

        @Test
        @DisplayName("size 상한은 주소 30·장소 15 로 다르다")
        void sizeLimitsDifferByEndpoint() {
            StubPort port = new StubPort();
            LocationService service = new LocationService(Optional.of(port));

            service.geocode("x", 0, 100, false);
            assertThat(port.addressQuery.size()).isEqualTo(30);

            service.searchPlaces("x", null, null, null, null, null, 0, 100);
            assertThat(port.keywordQuery.size()).isEqualTo(15);
        }

        @Test
        @DisplayName("size 를 안 보내면 주소 10·장소 15 가 기본이다")
        void defaultSizes() {
            StubPort port = new StubPort();
            LocationService service = new LocationService(Optional.of(port));

            service.geocode("x", 0, null, false);
            assertThat(port.addressQuery.size()).isEqualTo(10);

            service.searchPlaces("x", null, null, null, null, null, 0, null);
            assertThat(port.keywordQuery.size()).isEqualTo(15);
        }
    }

    // ────────────────────────────────────────────── DTO 매핑

    @Nested
    @DisplayName("DTO 매핑")
    class Mapping {

        @Test
        @DisplayName("좌표는 latitude·longitude 로 나간다 — x·y 라는 이름이 없다")
        void coordinateFieldNames() {
            assertThat(AddressResponse.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .contains("latitude", "longitude")
                    .doesNotContain("x", "y");
            assertThat(PlaceResponse.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .contains("latitude", "longitude")
                    .doesNotContain("x", "y");
        }

        @Test
        @DisplayName("도로명 주소가 없으면 roadAddress 는 null 이고 지번 주소는 남는다")
        void nullRoadAddressIsMapped() {
            StubPort port = new StubPort();
            port.reverseResult = Optional.of(new KakaoLocalPort.Address(
                    "경기 안성시 죽산면 죽산리 120-3", null,
                    new KakaoLocalPort.LandLotAddress("경기 안성시 죽산면 죽산리 120-3",
                            "경기", "안성시", "죽산리", "120", "3"),
                    null, null));

            List<AddressResponse> result = new LocationService(Optional.of(port))
                    .reverseGeocode(new BigDecimal("37.5"), new BigDecimal("127.0"));

            assertThat(result).hasSize(1);
            assertThat(result.get(0).roadAddress()).isNull();
            assertThat(result.get(0).landLotAddress().subAddressNo()).isEqualTo("3");
        }

        @Test
        @DisplayName("역지오코딩 결과가 없으면 빈 목록이다 — 404 를 만들지 않는다")
        void emptyReverseGeocodeIsEmptyList() {
            StubPort port = new StubPort();
            port.reverseResult = Optional.empty();

            assertThat(new LocationService(Optional.of(port))
                    .reverseGeocode(new BigDecimal("37.5"), new BigDecimal("127.0")))
                    .as("바다를 찍은 것은 오류가 아니다")
                    .isEmpty();
        }

        @Test
        @DisplayName("장소의 distanceMeters 가 그대로 실린다")
        void placeDistanceIsCarried() {
            StubPort port = new StubPort();
            port.placeResult = new KakaoLocalPort.Paged<>(List.of(new KakaoLocalPort.Place(
                    "1", "정비소", "자동차 > 정비", null, null, "서울 어딘가", null,
                    new KakaoLocalPort.Coordinate(new BigDecimal("37.5"), new BigDecimal("127.0")),
                    "http://place.map.kakao.com/1", 1273)), 1, 15, 1, 1, true);

            var response = new LocationService(Optional.of(port))
                    .searchPlaces("정비소", null, null, null, null, null, 0, 15);

            assertThat(response.content().get(0).distanceMeters()).isEqualTo(1273);
            assertThat(response.content().get(0).latitude()).isEqualByComparingTo("37.5");
        }
    }

    // ────────────────────────────────────────────── 요청 조립

    @Nested
    @DisplayName("요청 조립")
    class RequestAssembly {

        @Test
        @DisplayName("카테고리 검색은 좌표를 중심으로 넘기고 rect 는 쓰지 않는다")
        void categorySearchUsesCenter() {
            StubPort port = new StubPort();

            new LocationService(Optional.of(port)).searchPlacesByCategory(
                    CategoryGroupCode.PM9, new BigDecimal("37.5"), new BigDecimal("127.0"),
                    1000, null, 0, 15);

            assertThat(port.categoryQuery.categoryGroup().code()).isEqualTo("PM9");
            assertThat(port.categoryQuery.center().latitude()).isEqualByComparingTo("37.5");
            assertThat(port.categoryQuery.radius()).isEqualTo(1000);
            assertThat(port.categoryQuery.rect())
                    .as("이 API 는 rect 를 지원하지 않는다 — 지원하는 척하지 않는다")
                    .isNull();
        }

        @Test
        @DisplayName("sort 를 안 보내면 정확도순이다")
        void defaultSortIsAccuracy() {
            StubPort port = new StubPort();

            new LocationService(Optional.of(port))
                    .searchPlaces("x", null, null, null, null, null, 0, 15);

            assertThat(port.keywordQuery.sort()).isEqualTo(KakaoLocalPort.PlaceSort.ACCURACY);
        }

        @Test
        @DisplayName("radius 상한은 20000 으로 자른다")
        void radiusIsClamped() {
            StubPort port = new StubPort();

            new LocationService(Optional.of(port)).searchPlaces(
                    "x", new BigDecimal("37.5"), new BigDecimal("127.0"), 999_999, null, null, 0, 15);

            assertThat(port.keywordQuery.radius()).isEqualTo(20_000);
        }
    }

    // ────────────────────────────────────────────── 포트 부재

    @Nested
    @DisplayName("포트 부재")
    class PortAbsent {

        @Test
        @DisplayName("키가 없으면 503 이고 메시지에 키 관련 값이 없다")
        void serviceUnavailableWithoutKey() {
            LocationService service = new LocationService(Optional.empty());

            BusinessException e = org.assertj.core.api.Assertions.catchThrowableOfType(
                    BusinessException.class, () -> service.geocode("서울시청", 0, 10, false));

            assertThat(e.getErrorCode())
                    .isEqualTo(com.ssafy.a307.common.exception.ErrorCode.SERVICE_UNAVAILABLE);
            assertThat(e.getMessage()).doesNotContain("KakaoAK").doesNotContain("key");
        }

        /**
         * 검증이 포트 접근보다 <b>앞</b>에 있어야 한다. 뒤에 있으면 잘못된 요청도 카카오를
         * 한 번 부른 뒤 거절되고, 그것은 쿼터를 태운다.
         */
        @Test
        @DisplayName("잘못된 요청은 포트가 없어도 400 이다 — 검증이 호출보다 앞이라는 증거")
        void validationHappensBeforePortAccess() {
            LocationService service = new LocationService(Optional.empty());

            assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                    BusinessException.class,
                    () -> service.reverseGeocode(new BigDecimal("127.0"), new BigDecimal("37.5")))
                    .getErrorCode())
                    .as("축이 뒤집힌 좌표")
                    .isEqualTo(com.ssafy.a307.common.exception.ErrorCode.INVALID_REQUEST);

            assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
                    BusinessException.class,
                    () -> service.geocode("  ", 0, 10, false))
                    .getErrorCode())
                    .as("빈 질의어")
                    .isEqualTo(com.ssafy.a307.common.exception.ErrorCode.INVALID_REQUEST);
        }
    }

    // ────────────────────────────────────────────── 도구

    private static KakaoLocalPort.Address address() {
        return new KakaoLocalPort.Address("서울 중구 세종대로 110", "ROAD_ADDR", null, null,
                new KakaoLocalPort.Coordinate(new BigDecimal("37.5663"), new BigDecimal("126.9779")));
    }

    /** 포트를 흉내내며 <b>넘겨받은 요청을 그대로 보관한다</b> — 파라미터 조립을 검증하려고. */
    private static final class StubPort implements KakaoLocalPort {

        private KakaoLocalPort.AddressQuery addressQuery;
        private KakaoLocalPort.KeywordQuery keywordQuery;
        private KakaoLocalPort.CategoryQuery categoryQuery;

        private Paged<Address> addressResult =
                new Paged<>(List.of(address()), 1, 10, 1, 1, true);
        private Optional<Address> reverseResult = Optional.of(address());
        private Paged<Place> placeResult = new Paged<>(List.of(), 1, 15, 0, 0, true);

        @Override
        public Paged<Address> searchAddress(AddressQuery query) {
            this.addressQuery = query;
            return addressResult;
        }

        @Override
        public Optional<Address> reverseGeocode(Coordinate point) {
            return reverseResult;
        }

        @Override
        public Paged<Place> searchPlacesByKeyword(KeywordQuery query) {
            this.keywordQuery = query;
            return placeResult;
        }

        @Override
        public Paged<Place> searchPlacesByCategory(CategoryQuery query) {
            this.categoryQuery = query;
            return placeResult;
        }
    }
}
