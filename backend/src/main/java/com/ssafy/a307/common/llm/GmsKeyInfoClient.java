package com.ssafy.a307.common.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.client.RestClient;

/**
 * {@code GET {base}/key-info} 를 부른다.
 *
 * <pre>
 * GET  {base}/key-info
 * authorization: Bearer {GMS_KEY}
 * ->   { totalCredit, usedCredit, remainCredit, expiredDate }   // expiredDate 는 KST
 * </pre>
 *
 * <p><b>인증 헤더가 벤더와 무관하게 {@code Bearer} 다.</b> 이 엔드포인트는 GMS 자신의 것이라
 * Gemini 를 쓰더라도 {@code x-goog-api-key} 가 아니다. 벤더 클라이언트와 헤더를 공유하지 않고
 * 여기서 따로 만드는 이유다.
 *
 * <p>캐시·임계값 판정은 이 클래스가 하지 않는다 — {@link GmsCreditGuard} 의 몫이다.
 * 여기는 "한 번 불러 온다" 만 한다.
 */
@Slf4j
class GmsKeyInfoClient {

    private final GmsApiKey apiKey;
    private final RestClient restClient;

    GmsKeyInfoClient(GmsProperties properties, GmsApiKey apiKey, RestClient.Builder restClientBuilder) {
        this.apiKey = apiKey;
        this.restClient = restClientBuilder.baseUrl(properties.normalizedBaseUrl()).build();
    }

    /**
     * @return 조회한 키 정보
     * @throws RuntimeException 조회 실패. <b>부르는 쪽이 삼킨다</b> — 크레딧 조회가 안 된다고
     *                          본 작업을 막지 않는다({@link GmsCreditGuard})
     */
    GmsKeyInfo fetch() {
        GmsKeyInfo info = restClient.get()
                .uri("/key-info")
                .header("authorization", "Bearer " + apiKey.value())
                .retrieve()
                .body(GmsKeyInfo.class);
        if (info == null) {
            throw new IllegalStateException("key-info 응답이 비어 있다");
        }
        return info;
    }
}
