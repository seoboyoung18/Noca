package com.ssafy.a307.estimate.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

/**
 * 네이티브 쿼리가 돌려준 {@code TIMESTAMPTZ} 값을 {@link Instant} 로 맞춘다.
 *
 * <p><b>왜 필요한가</b> — 같은 {@code TIMESTAMP WITH TIME ZONE} 컬럼인데 드라이버마다 돌려주는 자바
 * 타입이 다르다. PostgreSQL 은 {@code Instant}, 테스트의 H2 는 {@code OffsetDateTime} 이다. 투영
 * 인터페이스의 반환 타입을 어느 한쪽으로 고정하면 다른 쪽에서 "Cannot project" 로 500 이 난다 —
 * 실제로 견적 조회({@code OffsetDateTime})가 H2 테스트만 통과하고 PostgreSQL 에서 깨져 있었다.
 * 그래서 투영은 {@code Object} 로 받고 여기서 한 번만 바꾼다.
 *
 * <p>SQL 에서 시간대 없는 {@code timestamp} 로 캐스팅하지 않은 것은 그 값이 DB 세션 시간대에 따라
 * 달라지기 때문이다.
 */
public final class NativeTimestamps {

    private NativeTimestamps() {
    }

    /** {@code null} 은 {@code null} 로 둔다. 모르는 타입이면 조용히 넘기지 않고 실패한다. */
    public static Instant toInstant(Object value) {
        return switch (value) {
            case null -> null;
            case Instant instant -> instant;
            case OffsetDateTime offset -> offset.toInstant();
            case ZonedDateTime zoned -> zoned.toInstant();
            case Timestamp timestamp -> timestamp.toInstant();
            default -> throw new IllegalStateException(
                    "지원하지 않는 시각 타입: " + value.getClass().getName());
        };
    }
}
