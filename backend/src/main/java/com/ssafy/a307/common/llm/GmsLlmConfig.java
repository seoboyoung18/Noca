package com.ssafy.a307.common.llm;

import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Clock;

/**
 * GMS LLM 계층 배선.
 *
 * <p><b>키가 없으면 아무 빈도 만들지 않는다</b>({@link GmsKeyPresentCondition}).
 * 그래도 <b>기동은 성공한다</b> — 키가 없다고 컨텍스트가 안 뜨면 차량·사고 API 까지 함께 죽는다.
 * 소비처는 이 저장소의 기존 관례대로 {@code Optional<LlmChatPort>} 로 받아 없으면 503 을 주면
 * 된다({@code EstimateFileValidationService.storage()} 와 같은 형태).
 *
 * <p><b>{@code RestClient.Builder} 를 이 계층 전용으로 새로 만든다.</b> 전역 빌더 빈을 주입받아
 * 변형하면 타임아웃 설정이 다른 곳까지 번진다. 여기서 만든 빌더에만 타임아웃을 심는다.
 */
@Slf4j
@Configuration
@Conditional(GmsKeyPresentCondition.class)
public class GmsLlmConfig {

    /**
     * 키를 담는 유일한 빈. {@link GmsProperties} 에 넣지 않은 이유는 그쪽 Javadoc 에 있다 —
     * record 의 {@code toString} 이 키를 로그에 흘린다.
     */
    @Bean
    GmsApiKey gmsApiKey(@Value("${app.gms.api-key:}") String apiKey) {
        return new GmsApiKey(apiKey);
    }

    /**
     * 이 계층 전용 HTTP 빌더. <b>타임아웃을 여기서 심는다.</b>
     *
     * <p>클라이언트 생성자가 아니라 빌더에 심는 이유는 테스트다. {@code MockRestServiceServer} 는
     * 빌더에 자기 요청 팩터리를 꽂는데, 클라이언트가 생성자에서 팩터리를 다시 설정하면
     * 그 대역을 덮어써 버린다.
     */
    @Bean
    RestClient.Builder gmsRestClientBuilder(GmsProperties properties) {
        return RestClient.builder().requestFactory(requestFactory(properties));
    }

    @Bean
    GmsKeyInfoClient gmsKeyInfoClient(
            GmsProperties properties, GmsApiKey apiKey, RestClient.Builder gmsRestClientBuilder) {
        return new GmsKeyInfoClient(properties, apiKey, gmsRestClientBuilder);
    }

    @Bean
    GmsCreditGuard gmsCreditGuard(
            GmsKeyInfoClient gmsKeyInfoClient, GmsProperties properties, Clock gmsClock) {
        GmsCreditGuard guard = new GmsCreditGuard(gmsKeyInfoClient, properties, gmsClock);
        // 기동 시 1회. 운영자가 "왜 갑자기 실패하는지" 를 로그에서 찾을 수 있어야 한다.
        guard.logStartupSnapshot();
        return guard;
    }

    /** 만료 판정에 쓰는 시계. 빈으로 뽑아 두면 테스트가 시간을 고정할 수 있다. */
    @Bean
    Clock gmsClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.gms", name = "provider", havingValue = "GEMINI")
    LlmChatPort geminiGmsClient(
            GmsProperties properties, GmsApiKey apiKey, ObjectMapper objectMapper,
            GmsCreditGuard gmsCreditGuard, RestClient.Builder gmsRestClientBuilder) {
        log.info("LLM 전송 계층: GEMINI (model={})", properties.model());
        return new GeminiGmsClient(properties, apiKey, objectMapper, gmsCreditGuard, gmsRestClientBuilder);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.gms", name = "provider", havingValue = "OPENAI")
    LlmChatPort openAiGmsClient(
            GmsProperties properties, GmsApiKey apiKey, ObjectMapper objectMapper,
            GmsCreditGuard gmsCreditGuard, RestClient.Builder gmsRestClientBuilder) {
        log.info("LLM 전송 계층: OPENAI (model={})", properties.model());
        return new OpenAiGmsClient(properties, apiKey, objectMapper, gmsCreditGuard, gmsRestClientBuilder);
    }

    private static ClientHttpRequestFactory requestFactory(GmsProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.connectTimeout());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }
}
