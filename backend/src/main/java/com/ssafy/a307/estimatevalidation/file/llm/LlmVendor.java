package com.ssafy.a307.estimatevalidation.file.llm;

import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 벤더 한 곳의 <b>전송 형식</b>만 담당한다 — 경로, 인증 헤더, 요청 본문 모양, 응답에서 텍스트를
 * 꺼내는 방법, 그리고 <b>PDF 를 직접 받을 수 있는지</b>.
 *
 * <p><b>호출부는 벤더를 몰라야 한다.</b> 어댑터는 이 인터페이스만 보고, 어느 구현이 꽂혔는지는
 * {@code app.estimate-ocr.provider} 가 정한다.
 *
 * <p><b>인증 헤더 이름이 벤더마다 다르다.</b> OpenAI 는 {@code Authorization: Bearer},
 * Gemini 는 {@code x-goog-api-key} 다. 그래서 추상화가 URL 뿐 아니라 <b>헤더까지</b> 감싼다 —
 * URL 만 갈아끼우는 구조였다면 벤더를 바꾸는 순간 401 이 나고 원인을 찾기 어렵다.
 *
 * <p><b>여기서 다루는 것은 형식뿐이다.</b> 지시문은 {@link EstimateOcrPrompt} 가, 응답 해석은
 * {@link OcrExtractionParser} 가 갖는다. 벤더를 하나 더 붙일 때 그 둘을 건드릴 일이 없어야 한다.
 */
public interface LlmVendor {

    /** {@code app.estimate-ocr.provider} 값과 비교할 이름. */
    String name();

    /**
     * GMS 프록시 기준 URL 뒤에 붙일 경로. <b>벤더의 원래 호스트까지 포함한다.</b>
     * GMS 는 벤더 엔드포인트 앞에 {@code https://gms.ssafy.io/gmsapi/} 를 붙이는 프록시라
     * 경로가 {@code /api.openai.com/v1/...} 처럼 호스트로 시작한다.
     *
     * <p>모델 이름이 경로에 들어가는 벤더(Gemini)가 있어 인자로 받는다.
     */
    String path(String model);

    /** 인증·버전 헤더. GMS 키를 벤더의 키 자리에 그대로 넣는다. */
    Map<String, String> headers(String apiKey);

    /**
     * @param mediaType 첨부의 MIME 타입. {@code image/jpeg} · {@code image/png} · {@code application/pdf}
     * @param base64    첨부 바이트의 base64
     */
    Map<String, Object> body(String model, String instruction, String mediaType, String base64, int maxOutputTokens);

    /**
     * 첨부 없이 지시문만 보내는 요청 본문. 요약 생성이 쓴다.
     *
     * <p>{@link #body} 와 나눈 이유는 벤더마다 <b>첨부를 싣는 자리가 다르기</b> 때문이다.
     * 첨부가 없는 요청에 빈 자리를 만들어 넣으면 벤더에 따라 400 이 난다.
     */
    Map<String, Object> textBody(String model, String instruction, int maxOutputTokens);

    /** 응답에서 모델이 낸 텍스트를 꺼낸다. 못 꺼내면 {@code null} 을 돌려 판독 실패로 만든다. */
    String extractText(JsonNode response);

    /**
     * PDF 를 그대로 실을 수 있는가.
     *
     * <p>{@code false} 인 벤더에 PDF 가 오면 어댑터가 <b>명시적으로 거절한다</b> —
     * 조용히 실패하거나 빈 결과를 내지 않는다. PDF 를 이미지로 렌더하려면 새 의존성이 필요하고
     * 그것은 이 작업의 범위가 아니다.
     */
    boolean supportsPdf();
}
