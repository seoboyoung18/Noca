package com.ssafy.a307.estimate.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("네이티브 쿼리 시각 변환")
class NativeTimestampsTest {

    private static final Instant AT = Instant.parse("2026-09-11T04:00:00Z");

    @Test
    @DisplayName("PostgreSQL 이 주는 Instant 는 그대로다")
    void instantAsIs() {
        assertThat(NativeTimestamps.toInstant(AT)).isEqualTo(AT);
    }

    @Test
    @DisplayName("H2 가 주는 OffsetDateTime 은 같은 시각의 Instant 가 된다")
    void offsetDateTime() {
        assertThat(NativeTimestamps.toInstant(AT.atOffset(ZoneOffset.ofHours(9)))).isEqualTo(AT);
    }

    @Test
    @DisplayName("ZonedDateTime·Timestamp 도 같은 시각으로 바뀐다")
    void zonedAndTimestamp() {
        assertThat(NativeTimestamps.toInstant(AT.atZone(ZoneId.of("Asia/Seoul")))).isEqualTo(AT);
        assertThat(NativeTimestamps.toInstant(Timestamp.from(AT))).isEqualTo(AT);
    }

    @Test
    @DisplayName("null 은 null 이다")
    void nullStaysNull() {
        assertThat(NativeTimestamps.toInstant(null)).isNull();
    }

    @Test
    @DisplayName("모르는 타입은 조용히 넘기지 않는다")
    void unknownTypeFails() {
        assertThatThrownBy(() -> NativeTimestamps.toInstant("2026-09-11"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("java.lang.String");
    }

    @Test
    @DisplayName("OffsetDateTime 의 오프셋이 달라도 같은 순간이면 같다")
    void offsetIndependent() {
        OffsetDateTime utc = AT.atOffset(ZoneOffset.UTC);
        OffsetDateTime kst = AT.atOffset(ZoneOffset.ofHours(9));
        assertThat(NativeTimestamps.toInstant(utc)).isEqualTo(NativeTimestamps.toInstant(kst));
    }
}
