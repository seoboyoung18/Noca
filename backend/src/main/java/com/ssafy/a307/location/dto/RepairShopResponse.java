package com.ssafy.a307.location.dto;

import java.math.BigDecimal;

/**
 * 주변 정비소 하나.
 *
 * <p>필드 이름은 범용 장소 검색({@link PlaceResponse})과 같다 — FE 가 지도 마커 코드를 두 번
 * 쓰지 않게. 다른 점은 둘이다. {@code categoryGroupCode} 가 없고(카카오 18종에 정비 업종이 없어
 * 항상 비어 있다), {@code distanceMeters} 가 <b>사실상 항상 채워진다</b>.
 *
 * <h2>⚠️ {@code latitude}/{@code longitude} 순서</h2>
 * 카카오 JS SDK 로 넘길 때는 {@code new kakao.maps.LatLng(latitude, longitude)} 다.
 *
 * @param placeId         카카오 장소 ID. <b>우리 DB 의 키가 아니다</b>
 * @param categoryName    {@code 자동차 > 자동차정비} 처럼 {@code >} 로 구분된 카카오 분류 경로
 * @param phone           <b>{@code null} 일 수 있다</b>
 * @param roadAddressName <b>{@code null} 일 수 있다</b>
 * @param distanceMeters  요청한 현재 위치로부터의 <b>직선거리(m)</b>. 카카오 값을 쓰고, 비어 오면
 *                        서버가 계산한다. 장소 좌표까지 없는 이상 응답에서만 {@code null} 이며
 *                        그런 행은 목록 맨 뒤에 온다
 */
public record RepairShopResponse(
        String placeId,
        String placeName,
        String categoryName,
        String phone,
        String addressName,
        String roadAddressName,
        BigDecimal latitude,
        BigDecimal longitude,
        String placeUrl,
        Integer distanceMeters) {

    public static RepairShopResponse of(PlaceResponse place, Integer distanceMeters) {
        return new RepairShopResponse(
                place.placeId(),
                place.placeName(),
                place.categoryName(),
                place.phone(),
                place.addressName(),
                place.roadAddressName(),
                place.latitude(),
                place.longitude(),
                place.placeUrl(),
                distanceMeters);
    }
}
