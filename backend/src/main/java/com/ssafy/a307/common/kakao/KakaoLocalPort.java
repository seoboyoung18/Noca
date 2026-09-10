package com.ssafy.a307.common.kakao;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * 카카오 로컬 API 한 번 호출. <b>도메인을 모르는 전송 경계다.</b>
 *
 * <h2>축 순서를 타입으로 못박는다</h2>
 * 이 연동에서 가장 사고가 잘 나는 지점이다.
 *
 * <table border="1">
 *   <caption>같은 두 숫자, 반대 순서</caption>
 *   <tr><th>주체</th><th>표기</th></tr>
 *   <tr><td>카카오 <b>REST</b> API</td><td>{@code x} = 경도(longitude), {@code y} = 위도(latitude)</td></tr>
 *   <tr><td>카카오 <b>JS SDK</b></td><td>{@code new kakao.maps.LatLng(위도, 경도)} — <b>순서가 반대</b></td></tr>
 * </table>
 *
 * <p>그래서 이 포트에는 <b>{@code x}/{@code y} 라는 이름이 없다.</b> {@link Coordinate} 가
 * {@code latitude}/{@code longitude} 로만 말하고, {@code x=longitude}·{@code y=latitude} 매핑은
 * {@link KakaoLocalClient} 안 한 곳에만 있다. 숫자 두 개를 나란히 넘기는 시그니처를 두지 않은
 * 것도 같은 이유다 — 순서를 바꿔 넣어도 컴파일이 통과하는 API 는 언젠가 바뀐다.
 *
 * <h2>좌표 타입을 하나로 통일한다</h2>
 * 카카오 응답의 좌표 타입은 엔드포인트마다 다르다 — {@code search/*} 는 <b>String</b>,
 * {@code geo/coord2regioncode}·{@code geo/transcoord} 는 <b>Double</b>. 이 포트는 전부
 * {@link BigDecimal} 로 통일한다. {@code double} 로 받으면 7자리 소수의 왕복에서 값이 흔들리고,
 * 문자열로 두면 소비처마다 파싱이 흩어진다. 파싱 실패는 <b>외부 응답 이상</b>으로 처리한다.
 *
 * <p><b>구현 빈은 키가 있을 때만 뜬다</b>({@link KakaoKeyPresentCondition}). 소비처는
 * {@code Optional<KakaoLocalPort>} 로 받아 없으면 503 을 주는 이 저장소의 기존 관례를 따른다.
 */
public interface KakaoLocalPort {

    /**
     * 주소 문자열 → 좌표.
     *
     * @throws KakaoLocalException 전송·응답 처리 실패. 어떤 {@code ErrorCode} 로 나갈지와
     *                             재시도 가능 여부를 담고 있다
     */
    Paged<Address> searchAddress(AddressQuery query);

    /**
     * 좌표 → 주소.
     *
     * @return 카카오 {@code meta.total_count} 가 <b>0 또는 1</b> 이다. 0 이면
     *         {@link Optional#empty()} — 바다나 미등록 구역일 수 있고 <b>오류가 아니다</b>
     */
    Optional<Address> reverseGeocode(Coordinate point);

    /** 키워드로 장소 검색. */
    Paged<Place> searchPlacesByKeyword(KeywordQuery query);

    /** 카테고리 그룹 코드로 장소 검색. */
    Paged<Place> searchPlacesByCategory(CategoryQuery query);

    // ── 좌표 ──────────────────────────────────────────────────────────────

    /**
     * 위도·경도 한 쌍. <b>{@code x}/{@code y} 로 부르지 않는다.</b>
     *
     * <p>대한민국 범위 검증은 여기 없다 — 그것은 전송 규칙이 아니라 도메인 규칙이라
     * {@code location} 계층이 카카오를 호출하기 전에 판단한다.
     *
     * @param latitude  위도. 한국은 대략 33~39
     * @param longitude 경도. 한국은 대략 124~132
     */
    record Coordinate(BigDecimal latitude, BigDecimal longitude) {

        public Coordinate {
            if (latitude == null) throw new IllegalArgumentException("latitude is required");
            if (longitude == null) throw new IllegalArgumentException("longitude is required");
        }
    }

    // ── 요청 ──────────────────────────────────────────────────────────────

    /**
     * @param query    검색 질의어
     * @param page     <b>카카오 규약 그대로 1-based</b>. 0-based ↔ 1-based 변환은 소비처가 하고,
     *                 이 포트는 카카오 문서와 같은 좌표계로 말한다 — 변환을 두 곳에서 하면
     *                 한쪽이 빠졌을 때 조용히 한 페이지가 밀린다
     * @param size     1~30 (주소 검색 상한)
     * @param exact    {@code true} 면 {@code analyze_type=exact}. 기본은 {@code similar}
     */
    record AddressQuery(String query, int page, int size, boolean exact) {

        public AddressQuery {
            if (query == null || query.isBlank()) {
                throw new IllegalArgumentException("query is required");
            }
            query = query.strip();
        }
    }

    /**
     * @param center 중심 좌표. {@code null} 이면 거리 정보가 없고 {@code radius}·거리순 정렬을
     *               쓸 수 없다
     * @param radius 미터. 0~20000. {@code center} 가 있을 때만 의미가 있다
     * @param sort   {@code accuracy} 또는 {@code distance}
     */
    record KeywordQuery(String query, Coordinate center, Integer radius,
                        PlaceSort sort, CategoryGroup categoryGroup, int page, int size) {

        public KeywordQuery {
            if (query == null || query.isBlank()) {
                throw new IllegalArgumentException("query is required");
            }
            query = query.strip();
            sort = sort == null ? PlaceSort.ACCURACY : sort;
        }
    }

    /**
     * @param categoryGroup 필수. 카카오가 정의한 18종 중 하나
     * @param center        {@code radius} 와 함께 쓴다. {@code rect} 를 쓰면 {@code null}
     * @param rect          {@code 좌X,좌Y,우X,우Y} 사각형. {@code center} 를 쓰면 {@code null}
     */
    record CategoryQuery(CategoryGroup categoryGroup, Coordinate center, Integer radius,
                         Rect rect, PlaceSort sort, int page, int size) {

        public CategoryQuery {
            if (categoryGroup == null) {
                throw new IllegalArgumentException("categoryGroup is required");
            }
            if (center == null && rect == null) {
                throw new IllegalArgumentException("center+radius 또는 rect 중 하나는 필요하다");
            }
            sort = sort == null ? PlaceSort.ACCURACY : sort;
        }
    }

    /**
     * 사각 영역. 카카오는 {@code 좌X,좌Y,우X,우Y} 순서를 요구한다 —
     * 여기서도 축 이름을 {@code longitude}/{@code latitude} 로 말하고 변환은 클라이언트가 한다.
     */
    record Rect(Coordinate southWest, Coordinate northEast) {

        public Rect {
            if (southWest == null || northEast == null) {
                throw new IllegalArgumentException("rect 는 두 좌표가 모두 필요하다");
            }
        }
    }

    /** 카카오 {@code sort} 파라미터. 문자열을 그대로 넘기지 않는다. */
    enum PlaceSort {
        ACCURACY("accuracy"),
        DISTANCE("distance");

        private final String parameter;

        PlaceSort(String parameter) {
            this.parameter = parameter;
        }

        public String parameter() {
            return parameter;
        }
    }

    /**
     * 전송 계층이 보는 카테고리 그룹 코드. 실제 18종 정의와 한글 표시명은
     * {@code location.domain.CategoryGroupCode} 에 있고, 이 포트는 코드 문자열만 알면 된다.
     */
    interface CategoryGroup {

        /** {@code MT1}·{@code PM9} 같은 카카오 코드. */
        String code();
    }

    // ── 응답 ──────────────────────────────────────────────────────────────

    /**
     * 주소 하나. 지번 주소와 도로명 주소를 함께 담는다.
     *
     * @param addressName 전체 주소 문자열
     * @param addressType 카카오 {@code address_type}. 좌표→주소 응답에는 없어 {@code null} 이다
     * @param landLot     지번 주소 상세. 없을 수 있다
     * @param road        도로명 주소 상세. <b>좌표에 따라 반환되지 않아 {@code null} 이 정상</b>이다
     * @param coordinate  좌표. 좌표→주소 요청에서는 우리가 보낸 좌표가 아니라 카카오가
     *                    돌려준 값이 없으므로 {@code null} 일 수 있다
     */
    record Address(String addressName, String addressType,
                   LandLotAddress landLot, RoadAddress road, Coordinate coordinate) {
    }

    /**
     * 지번 주소.
     *
     * @param mainAddressNo 주번지
     * @param subAddressNo  부번지. <b>카카오는 없을 때 {@code null} 이 아니라 빈 문자열을 준다</b> —
     *                      정규화는 클라이언트가 한다
     */
    record LandLotAddress(String addressName, String region1DepthName, String region2DepthName,
                          String region3DepthName, String mainAddressNo, String subAddressNo) {
    }

    /**
     * 도로명 주소.
     *
     * @param zoneNo 5자리 우편번호. <b>6자리 {@code address.zip_code} 는 Deprecated 라 쓰지 않는다</b>
     */
    record RoadAddress(String addressName, String region1DepthName, String region2DepthName,
                       String region3DepthName, String roadName,
                       String mainBuildingNo, String subBuildingNo,
                       String buildingName, String zoneNo) {
    }

    /**
     * 장소 하나.
     *
     * @param distanceMeters 중심 좌표를 넘겼을 때만 카카오가 채워 준다. 안 넘겼으면 빈 문자열이
     *                       오고 클라이언트가 {@code null} 로 정규화한다
     */
    record Place(String placeId, String placeName, String categoryName, String categoryGroupCode,
                 String phone, String addressName, String roadAddressName,
                 Coordinate coordinate, String placeUrl, Integer distanceMeters) {
    }

    /**
     * 카카오 {@code meta} + {@code documents} 를 담은 한 페이지.
     *
     * @param page          이 응답이 몇 페이지인가. <b>카카오와 같은 1-based</b>
     * @param totalCount    검색된 전체 문서 수
     * @param pageableCount <b>노출 가능한 문서 수. 장소 검색은 최대 45로 잘린다</b> —
     *                      {@code totalCount} 와 다를 수 있고, 의미가 달라 둘을 함께 남긴다
     * @param end           카카오 {@code is_end}. 현재 페이지가 마지막인가
     */
    record Paged<T>(List<T> items, int page, int size,
                    int totalCount, int pageableCount, boolean end) {

        public Paged {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
