package com.ssafy.a307.analysis.callback;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 내부 API(AI 서버 → 백엔드) 공유 비밀.
 *
 * <p><b>기본값을 두지 않는다.</b> 값이 없으면 {@link #configured()} 가 false 가 되고 callback 은
 * 전부 404 로 떨어진다. 빈 문자열을 "설정됨" 으로 보면 아무 토큰이나 통과하는 구멍이 된다 —
 * {@code app.object-storage.service-bucket} 을 {@code @ConditionalOnExpression} 으로 다루는
 * 것과 같은 이유다.
 *
 * <p>운영에서는 환경변수로 주입한다. 저장소에 값을 두지 않는다.
 */
@ConfigurationProperties(prefix = "app.internal-api")
public record InternalApiProperties(String token) {

    /** 토큰이 설정돼 있는가. 비어 있으면 내부 API 를 열지 않는다. */
    public boolean configured() {
        return token != null && !token.isBlank();
    }

    /**
     * 제시된 값이 맞는가.
     *
     * <p>길이가 다르면 일찍 끝나는 {@link String#equals} 대신 상수 시간 비교를 쓴다. 토큰 비교는
     * 타이밍으로 앞자리를 하나씩 맞춰 볼 수 있는 자리라, 값싼 방어를 굳이 빼지 않는다.
     */
    public boolean matches(String presented) {
        if (!configured() || presented == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
