package com.ssafy.a307.common.kakao;

import com.ssafy.a307.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;
import static org.hamcrest.Matchers.containsString;

/**
 * 카카오 로컬 전송 계층. <b>네트워크를 타지 않는다</b> — {@code MockRestServiceServer} 를
 * {@code RestClient.Builder} 에 바인딩해 요청을 가로챈다.
 *
 * <p>실제 카카오를 부르지 않는 이유는 셋이다. 쿼터가 공유 자원이고, 키 없이도 이 작업이
 * 끝나야 하며, 오류 코드({@code -10} 쿼터 초과 등)를 실제로 재현하려면 쿼터를 일부러
 * 바닥내야 한다.
 */
@DisplayName("카카오 로컬 전송 계층")
class KakaoLocalClientTest {

    private static final String KEY = "test-key-not-a-real-credential";
    private static final String BASE = "https://dapi.kakao.com";
    private static final String ADDRESS_URL = BASE + "/v2/local/search/address.json";
    private static final String COORD2ADDRESS_URL = BASE + "/v2/local/geo/coord2address.json";
    private static final String KEYWORD_URL = BASE + "/v2/local/search/keyword.json";
    private static final String CATEGORY_URL = BASE + "/v2/local/search/category.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ────────────────────────────────────────────────── 축 순서

    @Nested
    @DisplayName("축 순서 — x=경도, y=위도")
    class AxisOrder {

        /**
         * <b>이 테스트가 이 클래스의 존재 이유다.</b>
         *
         * <p>축을 바꿔 보내도 카카오는 HTTP 오류를 내지 않고 <b>엉뚱한 좌표의 정상 응답</b>을
         * 준다. 그래서 사람이 눈으로는 못 잡고, 쿼리 문자열을 직접 검증하는 테스트만이 잡는다.
         * {@code KakaoLocalClient.coordinateParams} 를 뒤집으면 여기서 깨진다.
         */
        @Test
        @DisplayName("역지오코딩 요청의 x 는 경도, y 는 위도다 — 뒤집으면 이 테스트가 깨진다")
        void reverseGeocodeSendsLongitudeAsX() {
            Fixture fixture = fixture(2);
            fixture.server.expect(once(), requestTo(containsString(COORD2ADDRESS_URL)))
                    .andExpect(queryParam("x", "127.1086228"))   // 경도
                    .andExpect(queryParam("y", "37.4012191"))    // 위도
                    .andRespond(withSuccess(coord2AddressWithRoad(), MediaType.APPLICATION_JSON));

            fixture.client.reverseGeocode(seoul());

            fixture.server.verify();
        }

        @Test
        @DisplayName("키워드 검색의 중심 좌표도 같은 순서다")
        void keywordSearchSendsLongitudeAsX() {
            Fixture fixture = fixture(2);
            fixture.server.expect(once(), requestTo(containsString(KEYWORD_URL)))
                    .andExpect(queryParam("x", "127.1086228"))
                    .andExpect(queryParam("y", "37.4012191"))
                    .andExpect(queryParam("radius", "500"))
                    .andRespond(withSuccess(keywordWithDistance(), MediaType.APPLICATION_JSON));

            fixture.client.searchPlacesByKeyword(new KakaoLocalPort.KeywordQuery(
                    "카카오프렌즈", seoul(), 500, KakaoLocalPort.PlaceSort.DISTANCE, null, 1, 15));

            fixture.server.verify();
        }

        @Test
        @DisplayName("응답의 x 는 longitude 로, y 는 latitude 로 읽는다")
        void responseAxisIsMappedBack() {
            Fixture fixture = fixture(2);
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andRespond(withSuccess(addressSearchBody(), MediaType.APPLICATION_JSON));

            var result = fixture.client.searchAddress(
                    new KakaoLocalPort.AddressQuery("전북 삼성동 100", 1, 10, false));

            var coordinate = result.items().get(0).coordinate();
            assertThat(coordinate.longitude()).isEqualByComparingTo("127.10860415615309");
            assertThat(coordinate.latitude()).isEqualByComparingTo("37.40128143786497");
        }
    }

    // ────────────────────────────────────────────────── 정상 경로

    @Nested
    @DisplayName("정상 응답 해석")
    class Success {

        @Test
        @DisplayName("주소 검색 — 지번·도로명 상세와 페이지 메타를 읽는다")
        void addressSearch() {
            Fixture fixture = fixture(2);
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andExpect(decodedQueryParam("query", "전북 삼성동 100"))
                    .andExpect(queryParam("analyze_type", "similar"))
                    .andExpect(queryParam("page", "1"))
                    .andExpect(queryParam("size", "10"))
                    .andRespond(withSuccess(addressSearchBody(), MediaType.APPLICATION_JSON));

            var result = fixture.client.searchAddress(
                    new KakaoLocalPort.AddressQuery("전북 삼성동 100", 1, 10, false));

            assertThat(result.totalCount()).isEqualTo(4);
            assertThat(result.pageableCount()).isEqualTo(4);
            assertThat(result.end()).isTrue();

            var address = result.items().get(0);
            assertThat(address.addressName()).isEqualTo("전북 익산시 부송동 100");
            assertThat(address.addressType()).isEqualTo("REGION_ADDR");
            assertThat(address.landLot().region1DepthName()).isEqualTo("전북");
            assertThat(address.landLot().mainAddressNo()).isEqualTo("100");
            assertThat(address.road().zoneNo()).isEqualTo("54547");
            assertThat(address.road().buildingName()).isEqualTo("한국전력공사");
        }

        @Test
        @DisplayName("exact 를 요청하면 analyze_type=exact 로 나간다")
        void exactAnalyzeType() {
            Fixture fixture = fixture(2);
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andExpect(queryParam("analyze_type", "exact"))
                    .andRespond(withSuccess(addressSearchBody(), MediaType.APPLICATION_JSON));

            fixture.client.searchAddress(new KakaoLocalPort.AddressQuery("전북 삼성동 100", 1, 10, true));

            fixture.server.verify();
        }

        @Test
        @DisplayName("역지오코딩 — 도로명 주소가 있으면 그것을 전체 주소로 쓴다")
        void reverseGeocodeWithRoadAddress() {
            Fixture fixture = fixture(2);
            fixture.server.expect(once(), requestTo(containsString(COORD2ADDRESS_URL)))
                    .andRespond(withSuccess(coord2AddressWithRoad(), MediaType.APPLICATION_JSON));

            Optional<KakaoLocalPort.Address> result = fixture.client.reverseGeocode(seoul());

            assertThat(result).isPresent();
            assertThat(result.get().addressName())
                    .as("도로명 주소가 있으면 그것이 전체 주소다 — 사용자가 저장할 값으로 더 유용하다")
                    .isEqualTo("경기도 안성시 죽산면 죽산초교길 69-4");
            assertThat(result.get().road().zoneNo()).isEqualTo("17519");
            assertThat(result.get().landLot().region3DepthName())
                    .as("coord2address 의 지번 주소는 region_3depth_name 에 면·리를 함께 담는다")
                    .isEqualTo("죽산면 죽산리");
            assertThat(result.get().addressType())
                    .as("좌표→주소 응답에는 address_type 이 없다. 지어내지 않는다")
                    .isNull();
        }

        /**
         * <b>{@code road_address} 가 {@code null} 인 것은 정상 흐름이다.</b>
         * 카카오는 좌표에 따라 도로명 주소를 반환하지 않는다. 여기서 NPE 로 500 이 나면
         * 지번 주소만 있는 지역이 통째로 조회 불가가 된다.
         */
        @Test
        @DisplayName("역지오코딩 — 도로명 주소가 null 이면 지번 주소로 fallback 한다 (500 이 아니다)")
        void reverseGeocodeWithoutRoadAddress() {
            Fixture fixture = fixture(2);
            fixture.server.expect(once(), requestTo(containsString(COORD2ADDRESS_URL)))
                    .andRespond(withSuccess(coord2AddressWithoutRoad(), MediaType.APPLICATION_JSON));

            Optional<KakaoLocalPort.Address> result = fixture.client.reverseGeocode(seoul());

            assertThat(result).isPresent();
            assertThat(result.get().road()).isNull();
            assertThat(result.get().addressName())
                    .as("도로명이 없으면 지번 주소가 전체 주소가 된다")
                    .isEqualTo("경기도 안성시 죽산면 죽산리 120-3");
        }

        @Test
        @DisplayName("역지오코딩 — total_count 0 은 빈 결과이고 오류가 아니다")
        void reverseGeocodeEmpty() {
            Fixture fixture = fixture(2);
            fixture.server.expect(once(), requestTo(containsString(COORD2ADDRESS_URL)))
                    .andRespond(withSuccess(coord2AddressEmpty(), MediaType.APPLICATION_JSON));

            assertThat(fixture.client.reverseGeocode(seoul()))
                    .as("바다나 미등록 구역을 고른 것은 정상 흐름이다")
                    .isEmpty();
        }

        @Test
        @DisplayName("키워드 검색 — 중심 좌표를 넘기면 distance 가 채워진다")
        void keywordSearchWithDistance() {
            Fixture fixture = fixture(2);
            fixture.server.expect(once(), requestTo(containsString(KEYWORD_URL)))
                    .andRespond(withSuccess(keywordWithDistance(), MediaType.APPLICATION_JSON));

            var result = fixture.client.searchPlacesByKeyword(new KakaoLocalPort.KeywordQuery(
                    "카카오프렌즈", seoul(), 500, KakaoLocalPort.PlaceSort.DISTANCE, null, 1, 15));

            var place = result.items().get(0);
            assertThat(place.placeId()).isEqualTo("26338954");
            assertThat(place.placeName()).isEqualTo("카카오프렌즈 반포한강공원점");
            assertThat(place.distanceMeters()).isEqualTo(1273);
            assertThat(place.phone()).isEqualTo("02-543-5548");
            assertThat(place.placeUrl()).isEqualTo("http://place.map.kakao.com/26338954");
        }

        /**
         * 카카오는 중심 좌표를 안 넘기면 {@code distance} 에 <b>빈 문자열</b>을 준다.
         * 그것을 그대로 흘리면 FE 가 {@code ""} 를 0m 로 오해할 수 있어 {@code null} 로 접는다.
         */
        @Test
        @DisplayName("키워드 검색 — 중심 좌표가 없으면 distance 는 null 이다 (빈 문자열이 아니다)")
        void keywordSearchWithoutDistance() {
            Fixture fixture = fixture(2);
            fixture.server.expect(once(), requestTo(containsString(KEYWORD_URL)))
                    .andRespond(withSuccess(keywordWithoutDistance(), MediaType.APPLICATION_JSON));

            var result = fixture.client.searchPlacesByKeyword(new KakaoLocalPort.KeywordQuery(
                    "카카오프렌즈", null, null, KakaoLocalPort.PlaceSort.ACCURACY, null, 1, 15));

            assertThat(result.items().get(0).distanceMeters()).isNull();
        }

        @Test
        @DisplayName("빈 문자열 필드는 null 로 접는다 — 카카오는 없을 때 \"\" 를 준다")
        void blankFieldsBecomeNull() {
            Fixture fixture = fixture(2);
            fixture.server.expect(once(), requestTo(containsString(KEYWORD_URL)))
                    .andRespond(withSuccess(keywordWithoutDistance(), MediaType.APPLICATION_JSON));

            var place = fixture.client.searchPlacesByKeyword(new KakaoLocalPort.KeywordQuery(
                    "x", null, null, null, null, 1, 15)).items().get(0);

            assertThat(place.phone()).as("phone 이 \"\" 로 왔다").isNull();
            assertThat(place.roadAddressName()).as("road_address_name 이 \"\" 로 왔다").isNull();
        }

        @Test
        @DisplayName("카테고리 검색 — 코드와 좌표가 함께 나간다")
        void categorySearch() {
            Fixture fixture = fixture(2);
            fixture.server.expect(once(), requestTo(containsString(CATEGORY_URL)))
                    .andExpect(queryParam("category_group_code", "PM9"))
                    .andExpect(queryParam("x", "127.1086228"))
                    .andExpect(queryParam("radius", "1000"))
                    .andRespond(withSuccess(keywordWithDistance(), MediaType.APPLICATION_JSON));

            var result = fixture.client.searchPlacesByCategory(new KakaoLocalPort.CategoryQuery(
                    () -> "PM9", seoul(), 1000, null, KakaoLocalPort.PlaceSort.DISTANCE, 1, 15));

            assertThat(result.items()).hasSize(1);
            fixture.server.verify();
        }
    }

    // ────────────────────────────────────────────────── 좌표 파싱

    @Nested
    @DisplayName("좌표 파싱 실패")
    class CoordinateParsing {

        /**
         * 숫자가 아닌 좌표를 {@code null} 이나 0 으로 넘기면 <b>FE 지도가 아프리카 앞바다에
         * 마커를 찍는다.</b> 조용히 넘기지 않고 외부 응답 이상으로 올린다.
         */
        @Test
        @DisplayName("숫자가 아닌 좌표는 외부 응답 이상으로 올린다 — 0 이나 null 로 넘기지 않는다")
        void malformedCoordinateFails() {
            Fixture fixture = fixture(1);
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andRespond(withSuccess("""
                            {"meta":{"total_count":1,"pageable_count":1,"is_end":true},
                             "documents":[{"address_name":"어딘가","address_type":"REGION",
                                           "x":"not-a-number","y":"37.0"}]}
                            """, MediaType.APPLICATION_JSON));

            assertThatThrownBy(() -> fixture.client.searchAddress(
                    new KakaoLocalPort.AddressQuery("어딘가", 1, 10, false)))
                    .isInstanceOf(KakaoLocalException.class)
                    .satisfies(e -> assertThat(((KakaoLocalException) e).errorCode())
                            .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        }
    }

    // ────────────────────────────────────────────────── 오류 매핑

    @Nested
    @DisplayName("카카오 오류 code 매핑")
    class ErrorMapping {

        @Test
        @DisplayName("-2 (파라미터 오류) → 400 INVALID_REQUEST, 재시도하지 않는다")
        void invalidParameterIsNotRetried() {
            Fixture fixture = fixture(3);
            // once() 라 두 번째 호출이 들어오면 검증에서 깨진다.
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andRespond(kakaoError(HttpStatus.BAD_REQUEST, -2));

            KakaoLocalException e = catchThrowableOfType(KakaoLocalException.class,
                    () -> fixture.client.searchAddress(
                            new KakaoLocalPort.AddressQuery("x", 1, 10, false)));

            assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
            assertThat(e.isRetryable()).isFalse();
            fixture.server.verify();
        }

        @Test
        @DisplayName("-10 (쿼터 초과) → 429 TOO_MANY_REQUESTS, 재시도하지 않는다")
        void quotaExceededIsNotRetried() {
            Fixture fixture = fixture(3);
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andRespond(kakaoError(HttpStatus.BAD_REQUEST, -10));

            KakaoLocalException e = catchThrowableOfType(KakaoLocalException.class,
                    () -> fixture.client.searchAddress(
                            new KakaoLocalPort.AddressQuery("x", 1, 10, false)));

            assertThat(e.errorCode())
                    .as("이미 바닥난 쿼터를 재시도로 더 태우면 복구가 늦어진다")
                    .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
            assertThat(e.isRetryable()).isFalse();
            fixture.server.verify();
        }

        @Test
        @DisplayName("-401 (앱키 오류) → 503, 재시도 없음, 응답 메시지에 키가 없다")
        void invalidAppKeyHidesTheKey() {
            Fixture fixture = fixture(3);
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andRespond(withUnauthorizedRequest()
                            .body("{\"code\":-401,\"msg\":\"invalid app key\"}")
                            .contentType(MediaType.APPLICATION_JSON));

            KakaoLocalException e = catchThrowableOfType(KakaoLocalException.class,
                    () -> fixture.client.searchAddress(
                            new KakaoLocalPort.AddressQuery("x", 1, 10, false)));

            assertThat(e.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
            assertThat(e.isRetryable()).isFalse();
            assertThat(e.getMessage())
                    .as("한 번 로그·응답에 남은 키는 되돌릴 수 없다")
                    .doesNotContain(KEY);
            assertThat(e.getMessage())
                    .as("카카오 원본 메시지를 그대로 흘리지 않는다")
                    .doesNotContain("invalid app key");
            fixture.server.verify();
        }

        @Test
        @DisplayName("-9798 (서비스 점검, 503) → 503")
        void maintenanceIsUnavailable() {
            Fixture fixture = fixture(1);
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andRespond(kakaoError(HttpStatus.SERVICE_UNAVAILABLE, -9798));

            KakaoLocalException e = catchThrowableOfType(KakaoLocalException.class,
                    () -> fixture.client.searchAddress(
                            new KakaoLocalPort.AddressQuery("x", 1, 10, false)));

            assertThat(e.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
            assertThat(e.isRetryable()).isFalse();
        }

        @Test
        @DisplayName("-7 (점검) 은 400 으로 오지만 재시도하지 않는다 — 상태 코드로는 구분할 수 없다")
        void maintenanceInBadRequestIsNotRetried() {
            Fixture fixture = fixture(3);
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andRespond(kakaoError(HttpStatus.BAD_REQUEST, -7));

            KakaoLocalException e = catchThrowableOfType(KakaoLocalException.class,
                    () -> fixture.client.searchAddress(
                            new KakaoLocalPort.AddressQuery("x", 1, 10, false)));

            assertThat(e.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
            fixture.server.verify();
        }

        @Test
        @DisplayName("-1 (내부 처리 에러) 은 재시도하고, 두 번째가 성공하면 200 이다")
        void internalErrorIsRetriedAndSucceeds() {
            Fixture fixture = fixture(2);
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andRespond(kakaoError(HttpStatus.BAD_REQUEST, -1));
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andRespond(withSuccess(addressSearchBody(), MediaType.APPLICATION_JSON));

            var result = fixture.client.searchAddress(
                    new KakaoLocalPort.AddressQuery("전북 삼성동 100", 1, 10, false));

            assertThat(result.items()).hasSize(1);
            fixture.server.verify();
        }

        @Test
        @DisplayName("-1 이 계속되면 시도 횟수를 소진하고 503 이다")
        void internalErrorExhaustsAttempts() {
            Fixture fixture = fixture(2);
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andRespond(kakaoError(HttpStatus.BAD_REQUEST, -1));
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andRespond(kakaoError(HttpStatus.BAD_REQUEST, -1));

            KakaoLocalException e = catchThrowableOfType(KakaoLocalException.class,
                    () -> fixture.client.searchAddress(
                            new KakaoLocalPort.AddressQuery("x", 1, 10, false)));

            assertThat(e.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
            fixture.server.verify();
        }

        @Test
        @DisplayName("-603 (플랫폼 타임아웃) 도 재시도 대상이다")
        void platformTimeoutIsRetried() {
            Fixture fixture = fixture(2);
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andRespond(kakaoError(HttpStatus.BAD_REQUEST, -603));
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andRespond(withSuccess(addressSearchBody(), MediaType.APPLICATION_JSON));

            assertThat(fixture.client.searchAddress(
                    new KakaoLocalPort.AddressQuery("전북 삼성동 100", 1, 10, false)).items())
                    .hasSize(1);
            fixture.server.verify();
        }

        @Test
        @DisplayName("매핑에 없는 code 는 503 이고 재시도하지 않는다 — 모르는 것을 다시 보내지 않는다")
        void unmappedCodeIsUnavailable() {
            Fixture fixture = fixture(3);
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andRespond(kakaoError(HttpStatus.BAD_REQUEST, -12345));

            KakaoLocalException e = catchThrowableOfType(KakaoLocalException.class,
                    () -> fixture.client.searchAddress(
                            new KakaoLocalPort.AddressQuery("x", 1, 10, false)));

            assertThat(e.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
            assertThat(e.isRetryable()).isFalse();
            fixture.server.verify();
        }

        @Test
        @DisplayName("본문이 JSON 이 아니어도 500 이 아니라 503 이다")
        void nonJsonErrorBodyIsHandled() {
            Fixture fixture = fixture(3);
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                            .body("<html>gateway error</html>")
                            .contentType(MediaType.TEXT_HTML));

            KakaoLocalException e = catchThrowableOfType(KakaoLocalException.class,
                    () -> fixture.client.searchAddress(
                            new KakaoLocalPort.AddressQuery("x", 1, 10, false)));

            assertThat(e.errorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("매핑 표의 모든 code 가 알려진 code 로 해석된다")
        void everyMappedCodeResolves() {
            for (KakaoApiErrorCode code : KakaoApiErrorCode.values()) {
                assertThat(KakaoApiErrorCode.of(code.code()))
                        .as("code %d", code.code())
                        .contains(code);
            }
            assertThat(KakaoApiErrorCode.of(null)).isEmpty();
            assertThat(KakaoApiErrorCode.of(-99999)).isEmpty();
        }

        @Test
        @DisplayName("재시도 대상은 -1 과 -603 뿐이다")
        void onlyTwoCodesAreRetryable() {
            assertThat(java.util.Arrays.stream(KakaoApiErrorCode.values())
                    .filter(KakaoApiErrorCode::isRetryable)
                    .map(Enum::name))
                    .containsExactlyInAnyOrder("INTERNAL_PROCESSING", "PLATFORM_TIMEOUT");
        }
    }

    // ────────────────────────────────────────────────── 인증 헤더·키 보호

    @Nested
    @DisplayName("인증 헤더와 키 보호")
    class Secrets {

        @Test
        @DisplayName("Authorization: KakaoAK {키} 로 인증한다 — Bearer 가 아니다")
        void authorizationHeader() {
            Fixture fixture = fixture(2);
            fixture.server.expect(once(), requestTo(containsString(ADDRESS_URL)))
                    .andExpect(header("Authorization", "KakaoAK " + KEY))
                    .andRespond(withSuccess(addressSearchBody(), MediaType.APPLICATION_JSON));

            fixture.client.searchAddress(new KakaoLocalPort.AddressQuery("x", 1, 10, false));

            fixture.server.verify();
        }

        @Test
        @DisplayName("키 객체의 toString 이 값을 내지 않는다")
        void keyToStringHidesValue() {
            KakaoRestApiKey key = new KakaoRestApiKey(KEY);

            assertThat(key.toString())
                    .doesNotContain(KEY)
                    .isEqualTo("KakaoRestApiKey[present=true]");
        }

        @Test
        @DisplayName("키가 없으면 헤더를 만들려 할 때 즉시 실패한다 — 빈 값을 보내 -401 을 받지 않는다")
        void missingKeyFailsFast() {
            KakaoRestApiKey empty = new KakaoRestApiKey("  ");

            assertThat(empty.isPresent()).isFalse();
            assertThatThrownBy(empty::authorizationHeader)
                    .isInstanceOf(IllegalStateException.class);
            assertThat(empty.toString()).isEqualTo("KakaoRestApiKey[present=false]");
        }

        @Test
        @DisplayName("설정 record 의 toString 에 키가 없다 — 키를 아예 담지 않는다")
        void propertiesDoNotHoldTheKey() {
            assertThat(properties(2).toString())
                    .doesNotContain(KEY)
                    .doesNotContain("restApiKey");
            assertThat(KakaoLocalProperties.class.getRecordComponents())
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .doesNotContain("restApiKey", "apiKey", "key");
        }
    }

    // ────────────────────────────────────────────────── 설정 검증

    @Nested
    @DisplayName("설정 검증")
    class Config {

        @Test
        @DisplayName("https 가 아닌 base-url 은 기동 시점에 걸린다")
        void plainHttpIsRejected() {
            var insecure = new KakaoLocalProperties(
                    "http://dapi.kakao.com", Duration.ofSeconds(1), Duration.ofSeconds(2), 2);

            assertThat(insecure.isBaseUrlSecure())
                    .as("이 통로로 REST API 키가 Authorization 헤더에 실려 나간다")
                    .isFalse();
        }

        @Test
        @DisplayName("타임아웃이 0 이나 음수면 걸린다 — 무한 대기를 만들지 않는다")
        void nonPositiveTimeoutIsRejected() {
            assertThat(new KakaoLocalProperties(BASE, Duration.ZERO, Duration.ofSeconds(1), 2)
                    .areDurationsPositive()).isFalse();
            assertThat(new KakaoLocalProperties(BASE, Duration.ofSeconds(1), Duration.ofSeconds(-1), 2)
                    .areDurationsPositive()).isFalse();
            assertThat(properties(2).areDurationsPositive()).isTrue();
        }

        @Test
        @DisplayName("기준 URL 의 꼬리 슬래시를 떼어 경로가 이중 슬래시가 되지 않게 한다")
        void trailingSlashIsTrimmed() {
            assertThat(new KakaoLocalProperties(
                    BASE + "/", Duration.ofSeconds(1), Duration.ofSeconds(2), 2)
                    .normalizedBaseUrl()).isEqualTo(BASE);
        }
    }

    // ────────────────────────────────────────────────── 도구

    private static KakaoLocalPort.Coordinate seoul() {
        return new KakaoLocalPort.Coordinate(
                new BigDecimal("37.4012191"), new BigDecimal("127.1086228"));
    }

    private static KakaoLocalProperties properties(int maxAttempts) {
        return new KakaoLocalProperties(
                BASE, Duration.ofSeconds(1), Duration.ofSeconds(2), maxAttempts);
    }

    private Fixture fixture(int maxAttempts) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        KakaoLocalPort client = new KakaoLocalClient(
                properties(maxAttempts), new KakaoRestApiKey(KEY), objectMapper, builder);
        return new Fixture(client, server);
    }

    private record Fixture(KakaoLocalPort client, MockRestServiceServer server) {
    }

    /**
     * 쿼리 파라미터를 <b>디코딩해서</b> 비교한다.
     *
     * <p>내장 {@code queryParam} 매처는 인코딩된 원문을 비교하므로 한글 질의어에는 쓸 수 없다.
     * 여기서 디코딩하면 "한글이 제대로 퍼센트 인코딩되어 나가고, 되돌려 읽으면 원문과 같다" 는
     * 것까지 한 번에 검증된다 — 인코딩이 깨지면 카카오가 엉뚱한 주소를 찾아 준다.
     */
    private static org.springframework.test.web.client.RequestMatcher decodedQueryParam(
            String name, String expected) {
        return request -> {
            String raw = org.springframework.web.util.UriComponentsBuilder
                    .fromUri(request.getURI()).build().getQueryParams().getFirst(name);
            String decoded = raw == null ? null
                    : org.springframework.web.util.UriUtils.decode(
                            raw, java.nio.charset.StandardCharsets.UTF_8);
            assertThat(decoded).as("쿼리 파라미터 %s", name).isEqualTo(expected);
        };
    }

    private static org.springframework.test.web.client.response.DefaultResponseCreator kakaoError(
            HttpStatus status, int kakaoCode) {
        return withStatus(status)
                .body("{\"code\":%d,\"msg\":\"kakao says something\"}".formatted(kakaoCode))
                .contentType(MediaType.APPLICATION_JSON);
    }

    // ── fixture 본문. 카카오 공식 문서의 응답 예시를 옮긴 것이다 ──────────────

    private static String addressSearchBody() {
        return """
                {
                  "meta": {"total_count": 4, "pageable_count": 4, "is_end": true},
                  "documents": [
                    {
                      "address_name": "전북 익산시 부송동 100",
                      "y": "37.40128143786497",
                      "x": "127.10860415615309",
                      "address_type": "REGION_ADDR",
                      "address": {
                        "address_name": "전북 익산시 부송동 100",
                        "region_1depth_name": "전북",
                        "region_2depth_name": "익산시",
                        "region_3depth_name": "부송동",
                        "region_3depth_h_name": "삼성동",
                        "h_code": "4514069000",
                        "b_code": "4514013400",
                        "mountain_yn": "N",
                        "main_address_no": "100",
                        "sub_address_no": "",
                        "x": "127.10860415615309",
                        "y": "37.40128143786497"
                      },
                      "road_address": {
                        "address_name": "전북 익산시 망산길 11-17",
                        "region_1depth_name": "전북",
                        "region_2depth_name": "익산시",
                        "region_3depth_name": "부송동",
                        "road_name": "망산길",
                        "underground_yn": "N",
                        "main_building_no": "11",
                        "sub_building_no": "17",
                        "building_name": "한국전력공사",
                        "zone_no": "54547",
                        "y": "37.401platform",
                        "x": "127.10largest"
                      }
                    }
                  ]
                }
                """;
    }

    private static String coord2AddressWithRoad() {
        return """
                {
                  "meta": {"total_count": 1},
                  "documents": [
                    {
                      "road_address": {
                        "address_name": "경기도 안성시 죽산면 죽산초교길 69-4",
                        "region_1depth_name": "경기",
                        "region_2depth_name": "안성시",
                        "region_3depth_name": "죽산면",
                        "road_name": "죽산초교길",
                        "underground_yn": "N",
                        "main_building_no": "69",
                        "sub_building_no": "4",
                        "building_name": "무지개",
                        "zone_no": "17519"
                      },
                      "address": {
                        "address_name": "경기도 안성시 죽산면 죽산리 120-3",
                        "region_1depth_name": "경기",
                        "region_2depth_name": "안성시",
                        "region_3depth_name": "죽산면 죽산리",
                        "mountain_yn": "N",
                        "main_address_no": "120",
                        "sub_address_no": "3",
                        "zip_code": ""
                      }
                    }
                  ]
                }
                """;
    }

    /** 도로명 주소가 없는 좌표. 지번 주소만 온다 — {@code null} 이 정상이다. */
    private static String coord2AddressWithoutRoad() {
        return """
                {
                  "meta": {"total_count": 1},
                  "documents": [
                    {
                      "road_address": null,
                      "address": {
                        "address_name": "경기도 안성시 죽산면 죽산리 120-3",
                        "region_1depth_name": "경기",
                        "region_2depth_name": "안성시",
                        "region_3depth_name": "죽산리",
                        "mountain_yn": "N",
                        "main_address_no": "120",
                        "sub_address_no": "3"
                      }
                    }
                  ]
                }
                """;
    }

    private static String coord2AddressEmpty() {
        return "{\"meta\":{\"total_count\":0},\"documents\":[]}";
    }

    private static String keywordWithDistance() {
        return """
                {
                  "meta": {"total_count": 1, "pageable_count": 1, "is_end": true,
                           "same_name": {"region": [], "keyword": "카카오프렌즈", "selected_region": ""}},
                  "documents": [
                    {
                      "id": "26338954",
                      "place_name": "카카오프렌즈 반포한강공원점",
                      "category_name": "가정,생활 > 문구,사무용품 > 팬시점",
                      "category_group_code": "",
                      "category_group_name": "",
                      "phone": "02-543-5548",
                      "address_name": "서울 서초구 반포동 245-5",
                      "road_address_name": "서울 서초구 신반포로11길 40",
                      "x": "127.00380043029785",
                      "y": "37.51269873333228",
                      "place_url": "http://place.map.kakao.com/26338954",
                      "distance": "1273"
                    }
                  ]
                }
                """;
    }

    /** 중심 좌표를 넘기지 않은 응답. {@code distance}·{@code phone} 이 빈 문자열이다. */
    private static String keywordWithoutDistance() {
        return """
                {
                  "meta": {"total_count": 1, "pageable_count": 1, "is_end": true},
                  "documents": [
                    {
                      "id": "26338954",
                      "place_name": "카카오프렌즈 반포한강공원점",
                      "category_name": "가정,생활 > 문구,사무용품 > 팬시점",
                      "category_group_code": "",
                      "phone": "",
                      "address_name": "서울 서초구 반포동 245-5",
                      "road_address_name": "",
                      "x": "127.00380043029785",
                      "y": "37.51269873333228",
                      "place_url": "http://place.map.kakao.com/26338954",
                      "distance": ""
                    }
                  ]
                }
                """;
    }
}
