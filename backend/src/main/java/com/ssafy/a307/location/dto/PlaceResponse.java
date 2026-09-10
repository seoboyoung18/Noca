package com.ssafy.a307.location.dto;

import com.ssafy.a307.common.kakao.KakaoLocalPort;

import java.math.BigDecimal;

/**
 * 장소 하나.
 *
 * <h2>⚠️ {@code latitude}/{@code longitude} 순서</h2>
 * FE 가 카카오 JS SDK 로 넘길 때는 {@code new kakao.maps.LatLng(latitude, longitude)} 다.
 * 카카오 REST 의 {@code x}/{@code y} 이름은 이 응답에 나타나지 않는다.
 *
 * @param placeId         카카오 장소 ID. <b>우리 DB 의 키가 아니다</b> — 저장하려면 별도 설계가 필요하다
 * @param categoryName    {@code 음식점 > 한식 > 국밥} 처럼 {@code >} 로 구분된 전체 경로
 * @param phone           <b>{@code null} 일 수 있다.</b> 카카오는 없을 때 빈 문자열을 주는데 접었다
 * @param roadAddressName 도로명 주소. <b>{@code null} 일 수 있다</b>
 * @param placeUrl        카카오맵 장소 상세 페이지. 그대로 새 창으로 열면 된다
 * @param distanceMeters  중심 좌표({@code latitude}·{@code longitude})를 <b>요청에 넘겼을 때만</b>
 *                        카카오가 채워 준다. 안 넘겼으면 {@code null} 이다 — 빈 문자열을 0 으로
 *                        오해하지 않게 정규화했다
 */
public record PlaceResponse(
        String placeId,
        String placeName,
        String categoryName,
        String categoryGroupCode,
        String phone,
        String addressName,
        String roadAddressName,
        BigDecimal latitude,
        BigDecimal longitude,
        String placeUrl,
        Integer distanceMeters) {

    public static PlaceResponse from(KakaoLocalPort.Place place) {
        return new PlaceResponse(
                place.placeId(),
                place.placeName(),
                place.categoryName(),
                place.categoryGroupCode(),
                place.phone(),
                place.addressName(),
                place.roadAddressName(),
                place.coordinate() == null ? null : place.coordinate().latitude(),
                place.coordinate() == null ? null : place.coordinate().longitude(),
                place.placeUrl(),
                place.distanceMeters());
    }
}
