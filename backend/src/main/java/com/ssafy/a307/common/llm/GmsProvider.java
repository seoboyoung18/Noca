package com.ssafy.a307.common.llm;

/**
 * GMS 를 통해 부를 벤더.
 *
 * <p><b>벤더가 둘로 갈리는 필연적인 이유는 인증 헤더뿐이다.</b> GMS 는 프록시라서 요청 본문과
 * 파라미터가 벤더 원본과 완전히 같고, 바뀌는 것은 엔드포인트와 키가 들어가는 헤더 이름이다.
 * 그래서 이 enum 이 고르는 것도 "어느 구현 빈을 띄울지" 하나뿐이다.
 *
 * <p><b>{@code CLAUDE} 를 넣지 않았다.</b> 확인된 GMS 문서가 경로와 인증을 명시한 것은 이 둘뿐이다.
 * 확인되지 않은 경로를 열거값으로 만들어 두면 <b>골라도 동작하지 않는 선택지</b>가 생긴다.
 * 경로가 확인되면 그때 값과 구현을 함께 늘린다.
 */
public enum GmsProvider {

    /**
     * {@code Authorization: Bearer {GMS_KEY}} ·
     * {@code {base}/api.openai.com/v1/responses} 또는 {@code /v1/chat/completions}
     */
    OPENAI,

    /**
     * {@code x-goog-api-key: {GMS_KEY}} ·
     * {@code {base}/generativelanguage.googleapis.com/v1beta/models/{model}:generateContent}
     */
    GEMINI
}
