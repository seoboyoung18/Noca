package com.ssafy.a307.common.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini {@code generateContent} 를 GMS 프록시로 부른다.
 *
 * <pre>
 * POST {base}/generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 * x-goog-api-key: {GMS_KEY}
 * </pre>
 *
 * <p><b>기본 벤더인 이유는 첨부 형식이다.</b> 이미지와 PDF 를 모두 {@code inline_data} 하나로
 * 받으므로 첨부 종류마다 요청 모양이 갈리지 않는다. 소비처가 jpg·png·pdf 를 섞어 보내는
 * 상황에서 분기가 줄어든다.
 *
 * <p><b>모델명이 URL 경로에 들어간다.</b> 이 벤더에서 경로 검증이 특히 중요한 이유다
 * ({@link #path}).
 */
class GeminiGmsClient extends GmsChatClientSupport {

    GeminiGmsClient(
            GmsProperties properties,
            GmsApiKey apiKey,
            ObjectMapper objectMapper,
            GmsCreditGuard creditGuard,
            RestClient.Builder restClientBuilder) {
        super(properties, apiKey, objectMapper, creditGuard, restClientBuilder);
    }

    @Override
    protected GmsProvider provider() {
        return GmsProvider.GEMINI;
    }

    /**
     * 벤더 호스트까지 포함한 경로. GMS 가 프록시라 원래 엔드포인트를 그대로 붙인다.
     *
     * <p><b>모델명을 그대로 이어 붙이지 않는다.</b> 여기에 {@code /} 나 {@code ..} 가 섞이면
     * 프록시 안에서 전혀 다른 엔드포인트로 새어 나갈 수 있다.
     */
    @Override
    protected String path(String model) {
        return "/generativelanguage.googleapis.com/v1beta/models/"
                + requireSafePathSegment(model) + ":generateContent";
    }

    /** <b>{@code Authorization: Bearer} 가 아니다.</b> 이 한 줄이 벤더 구현을 나누는 이유다. */
    @Override
    protected Map<String, String> authHeaders(String key) {
        return Map.of("x-goog-api-key", key);
    }

    @Override
    protected Map<String, Object> requestBody(ChatRequest request) {
        List<Map<String, Object>> parts = new ArrayList<>();
        parts.add(Map.of("text", request.instruction()));
        for (Attachment attachment : request.attachments()) {
            // 이미지와 PDF 가 같은 자리에 들어간다 — 이 벤더를 기본으로 둔 실질적 이유다.
            parts.add(Map.of("inline_data", Map.of(
                    "mime_type", attachment.mediaType(),
                    "data", java.util.Base64.getEncoder().encodeToString(attachment.content()))));
        }

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("maxOutputTokens", properties.maxOutputTokens());
        if (request.responseSchema() != null) {
            // 구조화 출력: responseMimeType + responseSchema 를 함께 줘야 스키마가 강제된다.
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseSchema", readSchema(request.responseSchema()));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("contents", List.of(Map.of("parts", parts)));
        body.put("generationConfig", generationConfig);
        return body;
    }

    /** 스키마 문자열은 이 계층이 해석하지 않는다. 트리로 바꿔 본문에 그대로 싣는다. */
    private JsonNode readSchema(JsonSchema schema) {
        try {
            return objectMapper.readTree(schema.schemaJson());
        } catch (Exception e) {
            throw new IllegalArgumentException("responseSchema 가 올바른 JSON 이 아니다: " + schema.name(), e);
        }
    }

    @Override
    protected String responseText(JsonNode response) {
        for (JsonNode part : response.path("candidates").path(0).path("content").path("parts")) {
            if (part.path("text").isTextual()) {
                return part.path("text").asText();
            }
        }
        return null;
    }

    @Override
    protected boolean isTruncated(JsonNode response) {
        return "MAX_TOKENS".equals(response.path("candidates").path(0).path("finishReason").asText());
    }

    @Override
    protected Usage usage(JsonNode response) {
        JsonNode usage = response.path("usageMetadata");
        if (!usage.hasNonNull("promptTokenCount") || !usage.hasNonNull("candidatesTokenCount")) {
            // 지어내지 않는다. 모르면 모른다고 둔다.
            return Usage.unknown();
        }
        return new Usage(usage.path("promptTokenCount").asInt(), usage.path("candidatesTokenCount").asInt());
    }

    @Override
    protected String respondedModel(JsonNode response) {
        JsonNode model = response.path("modelVersion");
        return model.isTextual() && !model.asText().isBlank() ? model.asText() : properties.model();
    }
}
