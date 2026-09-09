package com.ssafy.a307.estimatevalidation.file.llm;

import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SSAFY GMS 를 통해 부를 벤더 2종의 전송 형식.
 *
 * <p><b>GMS 는 LLM 이 아니라 프록시다.</b> 벤더의 원래 엔드포인트 앞에
 * {@code https://gms.ssafy.io/gmsapi/} 를 붙이고 벤더의 키 자리에 GMS 키를 넣는 것이 전부이며,
 * 요청 본문은 벤더 원본 그대로다. 그래서 경로에 벤더의 호스트가 그대로 들어간다.
 *
 * <p><b>{@code claude} 를 만들지 않는다.</b> GMS 문서가 경로와 인증을 명시한 것은
 * OpenAI · Gemini 둘뿐이고 Anthropic 경로는 없다. 확인되지 않은 경로를 지어 넣으면
 * <b>호출해 보기 전까지 동작하지 않는 것을 모르는 코드</b>가 남는다. 경로가 확인되면 그때 늘린다.
 *
 * <p><b>기본은 Gemini 다 — PDF 때문이다.</b> {@code EstimateFileValidator} 가 jpg·png·pdf 를
 * 허용하고 {@code OcrDocument} 가 {@code IMAGE}·{@code PDF} 를 모두 받는데, PDF 를 인라인으로
 * 그대로 받는 것은 Gemini 뿐이다. OpenAI 로 PDF 를 처리하려면 페이지를 이미지로 렌더해야 하고
 * 그것은 새 의존성이라 이 작업의 범위가 아니다.
 *
 * <p><b>스트리밍을 쓰지 않는다.</b> 판독 결과는 사용자에게 흘려보내는 응답이 아니라 서버가
 * 통째로 받아 파싱할 값이다. SSE 파서를 만들 이유가 없다.
 */
public final class LlmVendors {

    /** GMS 프록시 기준 URL. 벤더 경로가 이 뒤에 호스트째로 붙는다. */
    public static final String GMS_BASE_URL = "https://gms.ssafy.io/gmsapi";

    private LlmVendors() {
    }

    public static List<LlmVendor> all() {
        return List.of(new Gemini(), new OpenAi());
    }

    /**
     * Google Gemini {@code generateContent} 형식.
     * GMS 경로: {@code /generativelanguage.googleapis.com/v1beta/models/<model>:generateContent}
     */
    static final class Gemini implements LlmVendor {

        @Override
        public String name() {
            return "gemini";
        }

        @Override
        public String path(String model) {
            return "/generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
        }

        /** <b>{@code Authorization: Bearer} 가 아니다.</b> Gemini 는 전용 헤더를 쓴다. */
        @Override
        public Map<String, String> headers(String apiKey) {
            return Map.of("x-goog-api-key", apiKey);
        }

        @Override
        public Map<String, Object> body(
                String model, String instruction, String mediaType, String base64, int maxOutputTokens) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contents", List.of(Map.of("parts", List.of(
                    Map.of("text", instruction),
                    Map.of("inline_data", Map.of("mime_type", mediaType, "data", base64))))));
            body.put("generationConfig", Map.of("maxOutputTokens", maxOutputTokens));
            return body;
        }

        @Override
        public Map<String, Object> textBody(String model, String instruction, int maxOutputTokens) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("contents", List.of(Map.of("parts", List.of(Map.of("text", instruction)))));
            body.put("generationConfig", Map.of("maxOutputTokens", maxOutputTokens));
            return body;
        }

        @Override
        public String extractText(JsonNode response) {
            for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
                if (part.path("text").isTextual()) {
                    return part.path("text").asText();
                }
            }
            return null;
        }

        /**
         * {@code inline_data} 에 {@code application/pdf} 를 그대로 싣는다.
         *
         * <p>10MB 파일을 base64 로 실으면 약 13.3MB 로, Gemini 인라인 요청 상한(약 20MB) 안이라
         * {@code EstimateFileValidator} 의 10MB 제한과 맞는다. Files API 업로드 경로가 GMS
         * 프록시 대상인지는 확인되지 않아 쓰지 않는다.
         */
        @Override
        public boolean supportsPdf() {
            return true;
        }
    }

    /**
     * OpenAI Chat Completions 형식.
     * GMS 경로: {@code /api.openai.com/v1/chat/completions}
     */
    static final class OpenAi implements LlmVendor {

        @Override
        public String name() {
            return "openai";
        }

        @Override
        public String path(String model) {
            return "/api.openai.com/v1/chat/completions";
        }

        @Override
        public Map<String, String> headers(String apiKey) {
            return Map.of("Authorization", "Bearer " + apiKey);
        }

        @Override
        public Map<String, Object> body(
                String model, String instruction, String mediaType, String base64, int maxOutputTokens) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_completion_tokens", maxOutputTokens);
            body.put("messages", List.of(Map.of(
                    "role", "user",
                    "content", List.of(
                            Map.of("type", "text", "text", instruction),
                            Map.of("type", "image_url",
                                    "image_url", Map.of("url", dataUri(mediaType, base64)))))));
            return body;
        }

        @Override
        public Map<String, Object> textBody(String model, String instruction, int maxOutputTokens) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model);
            body.put("max_completion_tokens", maxOutputTokens);
            body.put("messages", List.of(Map.of("role", "user", "content", instruction)));
            return body;
        }

        @Override
        public String extractText(JsonNode response) {
            JsonNode content = response.path("choices").path(0).path("message").path("content");
            return content.isTextual() ? content.asText() : null;
        }

        /**
         * <b>PDF 를 직접 받지 못한다.</b> 페이지를 이미지로 렌더해야 하는데 그러려면 PDF 라이브러리를
         * 새로 넣어야 한다. 이 벤더를 고른 채 PDF 가 오면 어댑터가 명확히 거절한다.
         */
        @Override
        public boolean supportsPdf() {
            return false;
        }
    }

    private static String dataUri(String mediaType, String base64) {
        return "data:" + mediaType.toLowerCase(Locale.ROOT) + ";base64," + base64;
    }
}
