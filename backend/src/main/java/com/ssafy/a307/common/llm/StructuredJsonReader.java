package com.ssafy.a307.common.llm;

import tools.jackson.databind.ObjectMapper;

/**
 * 모델이 낸 텍스트를 JSON 으로 확정한다.
 *
 * <p><b>구조화 출력을 켜도 모델은 어긴다.</b> 그래서 벤더 기능을 쓰되 파싱은 방어적으로 한다.
 * 흔한 이탈 두 가지를 여기서 흡수한다.
 * <ul>
 *   <li>코드펜스로 감싸 오기 (<code>```json … ```</code>)</li>
 *   <li>앞뒤에 설명 문장을 붙여 오기</li>
 * </ul>
 *
 * <p><b>실패 사유를 뭉뚱그리지 않는다.</b> 빈 응답·잘린 응답·형식 위반은 소비처가 재시도할지
 * 포기할지를 가르는 서로 다른 신호다. 하나로 묶으면 소비처가 판단할 수 없다.
 *
 * <p><b>원문을 예외 메시지에 담지 않는다.</b> 응답 본문에는 견적서 내용이 들어 있고, 그 메시지는
 * 로그와 사용자 화면까지 갈 수 있다. 남기는 것은 길이 정도다.
 */
public class StructuredJsonReader {

    private final ObjectMapper objectMapper;

    public StructuredJsonReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param rawText 모델이 낸 텍스트
     * @return 파싱에 성공한 JSON 문자열 (코드펜스·앞뒤 설명이 벗겨진 상태)
     * @throws LlmChatException 비어 있거나({@code EMPTY_RESPONSE}) JSON 이 아닐 때
     *                          ({@code MALFORMED_RESPONSE}). 둘 다 재시도해도 같을 가능성이 높아
     *                          재시도 대상으로 표시하지 않는다 — 크레딧을 태우지 않는다
     */
    public String read(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new LlmChatException(
                    LlmChatException.Reason.EMPTY_RESPONSE, false, "모델 응답이 비어 있다");
        }
        String candidate = stripToJson(rawText);
        try {
            objectMapper.readTree(candidate);
        } catch (Exception e) {
            throw new LlmChatException(
                    LlmChatException.Reason.MALFORMED_RESPONSE, false,
                    "모델 응답을 JSON 으로 읽지 못했다 (길이 %d)".formatted(rawText.length()), e);
        }
        return candidate;
    }

    /**
     * 가장 바깥 중괄호 또는 대괄호 구간만 남긴다.
     *
     * <p>지시문에서 "JSON 만 출력하라" 고 해도 모델은 코드펜스와 인사말을 붙인다. 여기서 한 번
     * 벗기는 비용이 호출 하나를 통째로 버리는 것보다 훨씬 싸다 — 다시 부르면 크레딧이 든다.
     */
    private static String stripToJson(String rawText) {
        String text = rawText.strip();
        int objectStart = text.indexOf('{');
        int arrayStart = text.indexOf('[');
        boolean arrayFirst = arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart);

        int start = arrayFirst ? arrayStart : objectStart;
        int end = arrayFirst ? text.lastIndexOf(']') : text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
