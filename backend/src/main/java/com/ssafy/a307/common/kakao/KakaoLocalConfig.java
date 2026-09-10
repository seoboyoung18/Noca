package com.ssafy.a307.common.kakao;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 카카오 로컬 API 전송 계층 배선.
 *
 * <p><b>키가 없으면 아무 빈도 만들지 않는다</b>({@link KakaoKeyPresentCondition}).
 * 그래도 <b>기동은 성공한다</b> — 지도 키가 없다고 컨텍스트가 안 뜨면 차량·사고·견적 API 까지
 * 함께 죽는다. 소비처는 {@code Optional<KakaoLocalPort>} 로 받아 없으면 503 을 준다.
 *
 * <p><b>{@code RestClient.Builder} 를 이 계층 전용으로 새로 만든다.</b> 전역 빌더 빈을
 * 주입받아 변형하면 타임아웃 설정이 다른 곳까지 번진다. 여기서 만든 빌더에만 타임아웃을 심는다.
 * {@link com.ssafy.a307.common.llm.GmsLlmConfig} 와 같은 이유·같은 형태다.
 */
@Slf4j
@Configuration
@Conditional(KakaoKeyPresentCondition.class)
public class KakaoLocalConfig {

    /**
     * 키를 담는 유일한 빈. {@link KakaoLocalProperties} 에 넣지 않은 이유는 그쪽 Javadoc 에
     * 있다 — record 의 {@code toString} 이 키를 로그에 흘린다.
     */
    @Bean
    KakaoRestApiKey kakaoRestApiKey(
            @Value("${app.kakao.local.rest-api-key:}") String restApiKey) {
        return new KakaoRestApiKey(restApiKey);
    }

    /**
     * 이 계층 전용 HTTP 빌더. <b>타임아웃을 여기서 심는다.</b>
     *
     * <p>클라이언트 생성자가 아니라 빌더에 심는 이유는 테스트다. {@code MockRestServiceServer}
     * 는 빌더에 자기 요청 팩터리를 꽂는데, 클라이언트가 생성자에서 팩터리를 다시 설정하면
     * 그 대역을 덮어써 버린다.
     */
    @Bean
    RestClient.Builder kakaoLocalRestClientBuilder(KakaoLocalProperties properties) {
        return RestClient.builder().requestFactory(requestFactory(properties));
    }

    @Bean
    KakaoLocalPort kakaoLocalPort(KakaoLocalProperties properties, KakaoRestApiKey kakaoRestApiKey,
                                  ObjectMapper objectMapper,
                                  RestClient.Builder kakaoLocalRestClientBuilder) {
        // 키 값은 찍지 않는다. 켜졌다는 사실과 어디로 나가는지만 남긴다.
        log.info("카카오 로컬 연동 활성 (base-url={}, max-attempts={})",
                properties.normalizedBaseUrl(), properties.maxAttempts());
        return new KakaoLocalClient(properties, kakaoRestApiKey, objectMapper,
                kakaoLocalRestClientBuilder);
    }

    private static ClientHttpRequestFactory requestFactory(KakaoLocalProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }
}
