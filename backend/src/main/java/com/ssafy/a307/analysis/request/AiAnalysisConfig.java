package com.ssafy.a307.analysis.request;

import com.ssafy.a307.analysis.callback.InternalApiProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 서버 호출 배선 (S15P21A307-156).
 *
 * <p><b>{@code RestClient.Builder} 를 빈으로 내놓지 않는다.</b> 빌더 빈을 하나 더 만들면 스프링
 * 기본 빌더를 주입받던 곳이 이것을 받게 될 수 있다. 타임아웃을 심은 빌더는 이 클라이언트만 쓴다.
 */
@Configuration
public class AiAnalysisConfig {

    @Bean
    AiAnalysisClient aiAnalysisClient(AnalysisRequestProperties properties,
                                      InternalApiProperties internalApiProperties,
                                      ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        return new AiAnalysisClient(properties, internalApiProperties, objectMapper,
                RestClient.builder().requestFactory(factory));
    }
}
