package com.ssafy.a307.location.dto;

import com.ssafy.a307.common.kakao.KakaoLocalPort;

import java.math.BigDecimal;

/**
 * 주소 하나. 지오코딩과 역지오코딩이 같은 모양을 쓴다.
 *
 * <h2>⚠️ {@code latitude} 가 먼저다 — 카카오 REST 와 반대다</h2>
 * 카카오 REST 는 {@code x}=경도, {@code y}=위도로 말하지만 이 응답에는 그 이름이 없다.
 * <b>FE 가 카카오 JS SDK 로 넘길 때는 {@code new kakao.maps.LatLng(latitude, longitude)} 순서</b>다.
 * 두 필드를 바꿔 넣으면 한국 좌표가 인도양으로 가는데 오류는 나지 않는다.
 *
 * @param addressType {@code REGION}(지명) · {@code ROAD}(도로명) · {@code REGION_ADDR}(지번 주소) ·
 *                    {@code ROAD_ADDR}(도로명 주소). <b>역지오코딩 응답에는 없어 {@code null}</b> 이다 —
 *                    카카오가 주지 않는 값을 지어내지 않았다
 * @param roadAddress <b>{@code null} 일 수 있다.</b> 카카오는 좌표에 따라 도로명 주소를 반환하지
 *                    않는다. 지번 주소({@code landLotAddress})로 fallback 하면 된다
 * @param latitude    위도. 역지오코딩 응답에서는 요청에 실어 보낸 값이 그대로 오지 않으므로
 *                    {@code null} 이다
 */
public record AddressResponse(
        String addressName,
        String addressType,
        BigDecimal latitude,
        BigDecimal longitude,
        LandLotAddress landLotAddress,
        RoadAddress roadAddress) {

    /**
     * 지번 주소.
     *
     * @param subAddressNo 부번지. <b>없으면 {@code null}</b> 이다 — 카카오는 빈 문자열을 주는데
     *                     전송 계층이 접었다
     */
    public record LandLotAddress(
            String addressName,
            String region1DepthName,
            String region2DepthName,
            String region3DepthName,
            String mainAddressNo,
            String subAddressNo) {
    }

    /**
     * 도로명 주소.
     *
     * @param zoneNo 5자리 우편번호. 6자리 {@code zip_code} 는 Deprecated 라 제공하지 않는다
     */
    public record RoadAddress(
            String addressName,
            String region1DepthName,
            String region2DepthName,
            String region3DepthName,
            String roadName,
            String mainBuildingNo,
            String subBuildingNo,
            String buildingName,
            String zoneNo) {
    }

    public static AddressResponse from(KakaoLocalPort.Address address) {
        return new AddressResponse(
                address.addressName(),
                address.addressType(),
                address.coordinate() == null ? null : address.coordinate().latitude(),
                address.coordinate() == null ? null : address.coordinate().longitude(),
                landLot(address.landLot()),
                road(address.road()));
    }

    private static LandLotAddress landLot(KakaoLocalPort.LandLotAddress source) {
        if (source == null) return null;
        return new LandLotAddress(
                source.addressName(), source.region1DepthName(), source.region2DepthName(),
                source.region3DepthName(), source.mainAddressNo(), source.subAddressNo());
    }

    private static RoadAddress road(KakaoLocalPort.RoadAddress source) {
        if (source == null) return null;
        return new RoadAddress(
                source.addressName(), source.region1DepthName(), source.region2DepthName(),
                source.region3DepthName(), source.roadName(), source.mainBuildingNo(),
                source.subBuildingNo(), source.buildingName(), source.zoneNo());
    }
}
