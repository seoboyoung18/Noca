package com.ssafy.a307.common.kakao;

import com.ssafy.a307.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 카카오 로컬 REST API 전송 구현.
 *
 * <h2>축 매핑이 여기 한 곳에만 있다</h2>
 * {@code x=longitude}, {@code y=latitude}. 이 파일 밖에서 {@code x}/{@code y} 라는 이름이
 * 나오면 안 된다 — 위도·경도를 바꿔 넣은 한국 좌표는 <b>육지 밖으로 나가는데 오류는 아니라서</b>
 * 조용히 엉뚱한 결과가 돌아온다. {@link #coordinateParams} 가 유일한 통로다.
 *
 * <h2>본문 {@code code} 를 읽어 분기한다</h2>
 * 카카오는 실패를 대부분 HTTP 400 에 담고 원인은 본문 {@code code} 에 있다. 상태 코드만 보고
 * 재시도하면 <b>쿼터 초과({@code -10})에 재시도를 걸어 상황을 악화시킨다.</b>
 * 매핑 표는 {@link KakaoApiErrorCode} 에 있다.
 *
 * <h2>키를 어디에도 남기지 않는다</h2>
 * 로그에 남기는 것은 <b>엔드포인트 이름·시도 횟수·카카오 code</b> 뿐이다. 요청 URL 전문,
 * {@code Authorization} 헤더, 응답 본문은 남기지 않는다 — URL 에는 사용자가 검색한 주소가,
 * 헤더에는 키가 들어 있다.
 */
@Slf4j
public class KakaoLocalClient implements KakaoLocalPort {

    private static final String ADDRESS_PATH = "/v2/local/search/address.json";
    private static final String COORD2ADDRESS_PATH = "/v2/local/geo/coord2address.json";
    private static final String KEYWORD_PATH = "/v2/local/search/keyword.json";
    private static final String CATEGORY_PATH = "/v2/local/search/category.json";

    private static final long BASE_BACKOFF_MILLIS = 100L;
    private static final long MAX_BACKOFF_MILLIS = 800L;

    /** 사용자에게 나가는 문구. 카카오 원본 메시지를 그대로 흘리지 않는다. */
    private static final String UNAVAILABLE = "위치 정보 서비스를 일시적으로 사용할 수 없습니다.";
    private static final String QUOTA = "위치 정보 조회 한도를 초과했습니다. 잠시 후 다시 시도해 주세요.";
    private static final String BAD_REQUEST = "위치 정보 조회 요청이 올바르지 않습니다.";

    private final KakaoLocalProperties properties;
    private final KakaoRestApiKey apiKey;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public KakaoLocalClient(KakaoLocalProperties properties, KakaoRestApiKey apiKey,
                            ObjectMapper objectMapper, RestClient.Builder builder) {
        this.properties = properties;
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.restClient = builder.baseUrl(properties.normalizedBaseUrl()).build();
    }

    // ── 엔드포인트 ────────────────────────────────────────────────────────

    @Override
    public Paged<Address> searchAddress(AddressQuery query) {
        JsonNode body = get(ADDRESS_PATH, uri -> uri
                .queryParam("query", query.query())
                .queryParam("analyze_type", query.exact() ? "exact" : "similar")
                .queryParam("page", query.page())
                .queryParam("size", query.size()));
        return paged(body, query.page(), query.size(), KakaoLocalClient::readSearchedAddress);
    }

    @Override
    public Optional<Address> reverseGeocode(Coordinate point) {
        JsonNode body = get(COORD2ADDRESS_PATH, uri -> coordinateParams(uri, point));
        // meta.total_count 는 0 또는 1 이다. 0 은 바다·미등록 구역일 수 있고 오류가 아니다.
        JsonNode documents = body.path("documents");
        if (!documents.isArray() || documents.isEmpty()) return Optional.empty();
        return Optional.of(readReverseGeocoded(documents.get(0)));
    }

    @Override
    public Paged<Place> searchPlacesByKeyword(KeywordQuery query) {
        JsonNode body = get(KEYWORD_PATH, uri -> {
            uri.queryParam("query", query.query())
                    .queryParam("page", query.page())
                    .queryParam("size", query.size())
                    .queryParam("sort", query.sort().parameter());
            if (query.categoryGroup() != null) {
                uri.queryParam("category_group_code", query.categoryGroup().code());
            }
            if (query.center() != null) {
                coordinateParams(uri, query.center());
                if (query.radius() != null) uri.queryParam("radius", query.radius());
            }
        });
        return paged(body, query.page(), query.size(), KakaoLocalClient::readPlace);
    }

    @Override
    public Paged<Place> searchPlacesByCategory(CategoryQuery query) {
        JsonNode body = get(CATEGORY_PATH, uri -> {
            uri.queryParam("category_group_code", query.categoryGroup().code())
                    .queryParam("page", query.page())
                    .queryParam("size", query.size())
                    .queryParam("sort", query.sort().parameter());
            if (query.center() != null) {
                coordinateParams(uri, query.center());
                if (query.radius() != null) uri.queryParam("radius", query.radius());
            }
            if (query.rect() != null) uri.queryParam("rect", rect(query.rect()));
        });
        return paged(body, query.page(), query.size(), KakaoLocalClient::readPlace);
    }

    // ── 축 매핑 ───────────────────────────────────────────────────────────

    /**
     * <b>이 프로젝트에서 {@code x}·{@code y} 라는 이름이 나오는 유일한 곳.</b>
     *
     * <p>카카오 REST 는 {@code x} 가 경도, {@code y} 가 위도다. JS SDK 의
     * {@code LatLng(위도, 경도)} 와 순서가 반대라 사람이 헷갈리는 지점이고, 바꿔 넣어도
     * HTTP 오류가 아니라 <b>엉뚱한 좌표의 정상 응답</b>이 온다. 그래서 매핑을 함수 하나로
     * 좁혀 두고 테스트가 쿼리 문자열을 직접 검증한다.
     */
    private static UriBuilder coordinateParams(UriBuilder uri, Coordinate point) {
        return uri
                .queryParam("x", point.longitude().toPlainString())
                .queryParam("y", point.latitude().toPlainString());
    }

    /** 카카오 {@code rect} 는 {@code 좌X,좌Y,우X,우Y} = 남서 경도·위도, 북동 경도·위도 순서다. */
    private static String rect(Rect rect) {
        return "%s,%s,%s,%s".formatted(
                rect.southWest().longitude().toPlainString(),
                rect.southWest().latitude().toPlainString(),
                rect.northEast().longitude().toPlainString(),
                rect.northEast().latitude().toPlainString());
    }

    // ── 응답 읽기 ─────────────────────────────────────────────────────────

    private static <T> Paged<T> paged(JsonNode body, int page, int size,
                                      Function<JsonNode, T> reader) {
        JsonNode meta = body.path("meta");
        List<T> items = new ArrayList<>();
        for (JsonNode document : body.path("documents")) {
            items.add(reader.apply(document));
        }
        return new Paged<>(items, page, size,
                meta.path("total_count").asInt(0),
                meta.path("pageable_count").asInt(0),
                meta.path("is_end").asBoolean(true));
    }

    /** {@code search/address.json} 의 document. 좌표가 <b>문자열</b>로 온다. */
    private static Address readSearchedAddress(JsonNode document) {
        return new Address(
                text(document, "address_name"),
                text(document, "address_type"),
                readLandLot(document.path("address")),
                readRoad(document.path("road_address")),
                readCoordinate(document));
    }

    /**
     * {@code geo/coord2address.json} 의 document.
     *
     * <p><b>{@code address_type} 과 좌표가 없다.</b> 이 엔드포인트는 우리가 좌표를 준 것이라
     * 좌표를 되돌려주지 않는다. 없는 값을 지어내지 않고 {@code null} 로 둔다.
     */
    private static Address readReverseGeocoded(JsonNode document) {
        JsonNode road = document.path("road_address");
        String addressName = road.isObject() && !road.isNull()
                ? text(road, "address_name")
                : text(document.path("address"), "address_name");
        return new Address(addressName, null,
                readLandLot(document.path("address")), readRoad(road), null);
    }

    private static LandLotAddress readLandLot(JsonNode node) {
        if (!node.isObject()) return null;
        return new LandLotAddress(
                text(node, "address_name"),
                text(node, "region_1depth_name"),
                text(node, "region_2depth_name"),
                text(node, "region_3depth_name"),
                text(node, "main_address_no"),
                text(node, "sub_address_no"));
    }

    /**
     * 도로명 주소. <b>{@code null} 이 정상 흐름이다</b> — 카카오는 좌표에 따라 도로명 주소를
     * 반환하지 않는다. 여기서 NPE 로 500 을 내면 지번 주소만 있는 지역이 통째로 조회 불가가 된다.
     *
     * <p>6자리 {@code zip_code} 는 Deprecated 라 읽지 않는다. 우편번호는 5자리
     * {@code zone_no} 하나뿐이다.
     */
    private static RoadAddress readRoad(JsonNode node) {
        if (!node.isObject()) return null;
        return new RoadAddress(
                text(node, "address_name"),
                text(node, "region_1depth_name"),
                text(node, "region_2depth_name"),
                text(node, "region_3depth_name"),
                text(node, "road_name"),
                text(node, "main_building_no"),
                text(node, "sub_building_no"),
                text(node, "building_name"),
                text(node, "zone_no"));
    }

    private static Place readPlace(JsonNode document) {
        return new Place(
                text(document, "id"),
                text(document, "place_name"),
                text(document, "category_name"),
                text(document, "category_group_code"),
                text(document, "phone"),
                text(document, "address_name"),
                text(document, "road_address_name"),
                readCoordinate(document),
                text(document, "place_url"),
                distance(document));
    }

    /**
     * 문자열 좌표를 {@link BigDecimal} 로 읽는다.
     *
     * <p><b>파싱 실패를 무시하지 않는다.</b> 좌표가 비었거나 숫자가 아니면 카카오 응답이
     * 우리 기대와 다르다는 뜻이고, 그것을 {@code null} 이나 0 으로 넘기면 FE 지도가 아프리카
     * 앞바다에 마커를 찍는다. 외부 응답 이상으로 올린다.
     */
    private static Coordinate readCoordinate(JsonNode document) {
        String longitude = text(document, "x");
        String latitude = text(document, "y");
        if (longitude == null || latitude == null) return null;
        try {
            return new Coordinate(new BigDecimal(latitude), new BigDecimal(longitude));
        } catch (NumberFormatException e) {
            // 좌표 값 자체는 남긴다 — 키도 개인정보도 아니고, 원인 파악에 이것이 필요하다.
            throw new KakaoLocalException(ErrorCode.SERVICE_UNAVAILABLE, false,
                    UNAVAILABLE, e);
        }
    }

    /**
     * {@code distance} 는 <b>중심 좌표를 넘겼을 때만</b> 카카오가 채워 준다.
     * 안 넘겼으면 빈 문자열이 오므로 {@code null} 로 정규화한다 — 빈 문자열을 그대로 흘리면
     * FE 가 {@code ""} 를 0m 로 오해할 수 있다.
     */
    private static Integer distance(JsonNode document) {
        String raw = text(document, "distance");
        if (raw == null) return null;
        try {
            return Integer.valueOf(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 문자열 필드를 읽는다. <b>빈 문자열은 {@code null} 로 접는다.</b>
     *
     * <p>카카오는 {@code sub_address_no}·{@code sub_building_no}·{@code building_name}·
     * {@code phone}·{@code distance} 가 없을 때 {@code null} 이 아니라 <b>빈 문자열</b>을 준다.
     * 그것을 그대로 FE 에 흘리면 "값이 있는데 비어 있음" 과 "값이 없음" 을 구분할 수 없다.
     */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString()) return null;
        String raw = value.stringValue();
        return raw == null || raw.isBlank() ? null : raw.strip();
    }

    // ── 호출·재시도 ───────────────────────────────────────────────────────

    /**
     * 제한된 재시도.
     *
     * <p><b>{@code -1}·{@code -603}·전송 타임아웃에만 다시 보낸다.</b> 파라미터 오류나 쿼터
     * 초과를 재시도하면 같은 실패를 반복하면서 <b>쿼터만 더 태운다</b> — 카카오 로컬은 일일
     * 허용 회수가 있고 초과하면 서비스 전체가 멈춘다.
     */
    private JsonNode get(String path, Consumer<UriBuilder> params) {
        KakaoLocalException last = null;

        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                JsonNode response = restClient.get()
                        .uri(uri -> {
                            uri.path(path);
                            params.accept(uri);
                            return uri.build();
                        })
                        .header(HttpHeaders.AUTHORIZATION, apiKey.authorizationHeader())
                        .retrieve()
                        .body(JsonNode.class);
                if (response == null) {
                    throw new KakaoLocalException(ErrorCode.SERVICE_UNAVAILABLE, false, UNAVAILABLE);
                }
                return response;
            } catch (RestClientResponseException e) {
                last = mapFailure(path, e);
            } catch (ResourceAccessException e) {
                // 연결·읽기 타임아웃. 일시적일 수 있어 재시도 대상이다.
                last = new KakaoLocalException(ErrorCode.SERVICE_UNAVAILABLE, true, UNAVAILABLE, e);
            } catch (KakaoLocalException e) {
                last = e;
            }

            if (!last.isRetryable()) throw last;
            if (attempt < properties.maxAttempts()) backoff(attempt);
        }
        throw last;
    }

    /**
     * 카카오 실패를 프로젝트 오류로 번역한다.
     *
     * <p>본문 {@code code} 를 먼저 본다. 상태 코드는 참고만 한다 — 카카오는 쿼터 초과도 400,
     * 점검도 400 으로 주기 때문에 상태 코드로는 구분할 수 없다.
     */
    private KakaoLocalException mapFailure(String path, RestClientResponseException e) {
        Integer kakaoCode = readErrorCode(e.getResponseBodyAsString());
        Optional<KakaoApiErrorCode> mapped = KakaoApiErrorCode.of(kakaoCode);

        if (mapped.isEmpty()) {
            // 모르는 코드를 임의로 분류하지 않는다. 원본 code·status 는 남긴다 — 키가 아니다.
            log.warn("카카오 로컬 미정의 오류: endpoint={}, status={}, code={}",
                    path, e.getStatusCode().value(), kakaoCode);
            return new KakaoLocalException(KakaoApiErrorCode.unmappedFallback(), false, UNAVAILABLE, e);
        }

        KakaoApiErrorCode reason = mapped.get();
        if (reason.needsOperatorAttention()) {
            // 사용자가 무엇을 해도 해결되지 않는 사유. 운영자가 봐야 한다.
            log.error("카카오 로컬 설정·권한 문제: endpoint={}, code={} ({}). "
                            + "카카오 앱 관리 페이지에서 키·API 권한·활성 상태를 확인해야 한다.",
                    path, reason.code(), reason.name());
        } else {
            log.warn("카카오 로컬 호출 실패: endpoint={}, code={} ({}), 재시도가능={}",
                    path, reason.code(), reason.name(), reason.isRetryable());
        }
        return new KakaoLocalException(reason.errorCode(), reason.isRetryable(),
                userMessage(reason.errorCode()), e);
    }

    /**
     * 응답 본문에서 {@code code} 만 꺼낸다.
     *
     * <p>본문 전체를 로그에 남기지 않는 이유는 그 안에 카카오 원본 메시지와 우리가 보낸
     * 파라미터가 함께 들어 있을 수 있기 때문이다. 필요한 것은 숫자 하나다.
     */
    private Integer readErrorCode(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode node = objectMapper.readTree(body).path("code");
            return node.isNumber() ? node.asInt() : null;
        } catch (RuntimeException e) {
            // Jackson 3 의 파싱 예외는 RuntimeException 하위다. 본문이 JSON 이 아니어도
            // 여기서 터지면 안 된다 — 코드를 못 읽었다는 사실만으로 미정의 처리로 넘긴다.
            return null;
        }
    }

    private static String userMessage(ErrorCode errorCode) {
        return switch (errorCode) {
            case TOO_MANY_REQUESTS -> QUOTA;
            case INVALID_REQUEST -> BAD_REQUEST;
            default -> UNAVAILABLE;
        };
    }

    /**
     * 짧은 지수 백오프에 지터.
     *
     * <p>사용자 요청 스레드에서 도는 호출이라 대기가 곧 응답 지연이다. 상한을
     * {@value #MAX_BACKOFF_MILLIS}ms 로 묶고, 시도 횟수도 {@code max-attempts}(상한 3)로 제한한다.
     */
    private static void backoff(int attempt) {
        long exponential = Math.min(BASE_BACKOFF_MILLIS << (attempt - 1), MAX_BACKOFF_MILLIS);
        long jittered = ThreadLocalRandom.current().nextLong(BASE_BACKOFF_MILLIS, exponential + 1);
        try {
            Thread.sleep(jittered);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KakaoLocalException(ErrorCode.SERVICE_UNAVAILABLE, false, UNAVAILABLE, e);
        }
    }
}
