package com.ssafy.a307.location.domain;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.kakao.KakaoLocalPort;

import java.math.BigDecimal;

/**
 * 좌표가 대한민국 안에 있는지 보는 <b>느슨한 sanity check</b>.
 *
 * <h2>왜 이것이 필요한가 — 축 뒤집힘은 오류로 드러나지 않는다</h2>
 * 카카오 REST 는 {@code x}=경도, JS SDK 는 {@code LatLng(위도, 경도)} 로 순서가 반대다.
 * 서울을 예로 들면 위도 37.5, 경도 127.0 인데 이것을 바꿔 넣으면 <b>위도 127·경도 37.5</b> 가
 * 되고, 그 좌표는 <b>인도양 한가운데</b>다. 카카오는 그것을 오류로 보지 않고 "결과 없음" 을
 * 정상 응답으로 돌려준다. 즉 <b>버그가 조용히 빈 결과로 위장된다.</b>
 *
 * <p>그래서 축이 뒤집힌 입력을 카카오에 보내기 <b>전에</b> 400 으로 끊는다. 쿼터도 아끼고,
 * FE 는 "결과 없음" 대신 "좌표가 잘못됐다" 는 신호를 받는다.
 *
 * <h2>범위를 넓게 잡은 이유</h2>
 * 위도 {@value #MIN_LATITUDE}~{@value #MAX_LATITUDE}, 경도
 * {@value #MIN_LONGITUDE}~{@value #MAX_LONGITUDE} 는 마라도·독도·백령도를 포함하는
 * 넉넉한 사각형이다. <b>국경을 정밀하게 판정하려는 것이 아니다</b> — 목적은 축 뒤집힘과
 * 명백한 오타를 걸러내는 것이고, 좁게 잡으면 정상적인 도서 지역 좌표를 거절하게 된다.
 */
public final class KoreaBounds {

    public static final String MIN_LATITUDE = "33.0";
    public static final String MAX_LATITUDE = "39.0";
    public static final String MIN_LONGITUDE = "124.0";
    public static final String MAX_LONGITUDE = "132.0";

    private static final BigDecimal LAT_MIN = new BigDecimal(MIN_LATITUDE);
    private static final BigDecimal LAT_MAX = new BigDecimal(MAX_LATITUDE);
    private static final BigDecimal LON_MIN = new BigDecimal(MIN_LONGITUDE);
    private static final BigDecimal LON_MAX = new BigDecimal(MAX_LONGITUDE);

    private KoreaBounds() {
    }

    /**
     * 범위 안이면 좌표를 만들어 돌려주고, 벗어나면 400 이다.
     *
     * <p>오류 메시지에 <b>위도·경도를 바꿔 넣은 것이 아닌지 묻는 문장</b>을 넣었다. 이 검증이
     * 실제로 걸리는 가장 흔한 원인이 그것이라, 범위 숫자만 알려주면 FE 가 원인을 못 찾는다.
     */
    public static KakaoLocalPort.Coordinate require(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null || longitude == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "latitude 와 longitude 는 필수입니다.");
        }
        if (!within(latitude, LAT_MIN, LAT_MAX) || !within(longitude, LON_MIN, LON_MAX)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    ("좌표가 대한민국 범위를 벗어났습니다. 위도는 %s~%s, 경도는 %s~%s 입니다. "
                            + "위도와 경도를 바꿔 보내지 않았는지 확인해 주세요 "
                            + "(latitude=%s, longitude=%s).")
                            .formatted(MIN_LATITUDE, MAX_LATITUDE, MIN_LONGITUDE, MAX_LONGITUDE,
                                    latitude.toPlainString(), longitude.toPlainString()));
        }
        return new KakaoLocalPort.Coordinate(latitude, longitude);
    }

    private static boolean within(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }
}
