package com.ssafy.a307.location.domain;

import com.ssafy.a307.common.kakao.KakaoLocalPort;

/**
 * 두 좌표 사이의 <b>직선(대원) 거리</b>. 도로를 따라가는 주행 거리가 아니다.
 *
 * <h2>언제 쓰는가 — 카카오가 거리를 주지 않았을 때만</h2>
 * 중심 좌표를 넘기면 카카오가 장소마다 {@code distance}(m)를 채워 주고, 거리순 정렬도 그 값으로
 * 한다. 정비소 목록은 <b>그 값을 우선</b> 쓴다 — 서버가 따로 계산한 값으로 바꾸면 카카오가
 * 페이지를 나눈 순서와 미터 단위 반올림이 어긋나 페이지 경계에서 순서가 뒤집힐 수 있다.
 * 이 계산은 카카오 응답에 {@code distance} 가 비어 온 예외 경우를 메우는 용도다.
 *
 * <h2>하버사인 공식을 쓴다</h2>
 * 지구를 반지름 {@value #EARTH_RADIUS_METERS}m(국제측지학협회 평균 반지름) 구로 본다. 타원체 보정을
 * 하지 않아 수 km 거리에서 0.5% 안팎 차이가 날 수 있지만, "가까운 정비소 순서" 를 정하는 데에는
 * 충분하고 새 의존성이 필요 없다.
 */
public final class GeoDistance {

    static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoDistance() {
    }

    /** @return 반올림한 미터. 카카오 {@code distance} 와 같은 단위다 */
    public static int meters(KakaoLocalPort.Coordinate from, KakaoLocalPort.Coordinate to) {
        double fromLatitude = Math.toRadians(from.latitude().doubleValue());
        double toLatitude = Math.toRadians(to.latitude().doubleValue());
        double deltaLatitude = toLatitude - fromLatitude;
        double deltaLongitude = Math.toRadians(
                to.longitude().doubleValue() - from.longitude().doubleValue());

        double haversine = Math.sin(deltaLatitude / 2) * Math.sin(deltaLatitude / 2)
                + Math.cos(fromLatitude) * Math.cos(toLatitude)
                * Math.sin(deltaLongitude / 2) * Math.sin(deltaLongitude / 2);
        double centralAngle = 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
        return (int) Math.round(EARTH_RADIUS_METERS * centralAngle);
    }
}
