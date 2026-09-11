package com.ssafy.a307.location.domain;

import com.ssafy.a307.common.kakao.KakaoLocalPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("직선 거리 계산")
class GeoDistanceTest {

    private static KakaoLocalPort.Coordinate at(String latitude, String longitude) {
        return new KakaoLocalPort.Coordinate(new BigDecimal(latitude), new BigDecimal(longitude));
    }

    @Test
    @DisplayName("같은 점은 0m 다")
    void samePointIsZero() {
        assertThat(GeoDistance.meters(at("37.5665", "126.9780"), at("37.5665", "126.9780"))).isZero();
    }

    @Test
    @DisplayName("위도 0.01° 는 약 1,112m 다 — 경도가 같을 때 위도와 무관하다")
    void oneHundredthDegreeOfLatitude() {
        assertThat(GeoDistance.meters(at("37.0000", "127.0000"), at("37.0100", "127.0000")))
                .isEqualTo(1_112);
        assertThat(GeoDistance.meters(at("33.5000", "126.5000"), at("33.5100", "126.5000")))
                .isEqualTo(1_112);
    }

    @Test
    @DisplayName("경도 1° 는 위도가 높을수록 짧다 — 위도·경도를 바꿔 쓰면 이 차이가 사라진다")
    void longitudeShrinksWithLatitude() {
        int nearEquatorLike = GeoDistance.meters(at("33.0", "126.0"), at("33.0", "127.0"));
        int northern = GeoDistance.meters(at("38.0", "126.0"), at("38.0", "127.0"));

        assertThat(northern).isLessThan(nearEquatorLike);
        // cos(37.5°) × 111.2km ≈ 88.2km
        assertThat(GeoDistance.meters(at("37.5", "126.5"), at("37.5", "127.5"))).isBetween(88_000, 88_400);
    }

    @Test
    @DisplayName("방향과 무관하다")
    void isSymmetric() {
        var cityHall = at("37.5665", "126.9780");
        var gangnam = at("37.4979", "127.0276");

        assertThat(GeoDistance.meters(cityHall, gangnam)).isEqualTo(GeoDistance.meters(gangnam, cityHall));
        // 서울시청 ↔ 강남역 직선거리 약 8.8km
        assertThat(GeoDistance.meters(cityHall, gangnam)).isBetween(8_600, 9_000);
    }
}
