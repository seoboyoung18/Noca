package com.ssafy.a307.common.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI <b>Responses API</b> 를 GMS 프록시로 부른다.
 *
 * <pre>
 * POST {base}/api.openai.com/v1/responses
 * Authorization: Bearer {GMS_KEY}
 * </pre>
 *
 * <p><b>왜 {@code /v1/chat/completions} 가 아니라 {@code /v1/responses} 인가</b>
 * <ul>
 *   <li>GMS 문서가 예시로 보여 준 OpenAI 경로가 이것이다</li>
 *   <li>이 계층이 요구하는 두 가지 — <b>구조화 출력</b>({@code text.format} 의 {@code json_schema})과
 *       <b>파일 입력</b>({@code input_file}) — 을 한 요청 모양으로 만족한다.
 *       {@code chat/completions} 로 PDF 를 보내려면 별도 파일 업로드 경로가 필요하고,
 *       그 경로가 GMS 프록시 대상인지 확인되지 않았다</li>
 * </ul>
 * 이 선택의 근거는 answer37 2장에 적었다.
 *
 * <p><b>모델명이 경로에 들어가지 않는다.</b> 본문 필드다. Gemini 와 다른 점이다.
 */
class OpenAiGmsClient extends GmsChatClientSupport {

    private static final String PDF_MEDIA_TYPE = "application/pdf";

    OpenAiGmsClient(
            GmsProperties properties,
            GmsApiKey apiKey,
            ObjectMapper objectMapper,
            GmsCreditGuard creditGuard,
            RestClient.Builder restClientBuilder) {
        super(properties, apiKey, objectMapper, creditGuard, restClientBuilder);
    }

    @Override
    protected GmsProvider provider() {
        return GmsProvider.OPENAI;
    }

    @Override
    protected String path(String model) {
        // 모델명은 경로에 들어가지 않지만, 설정값 검증은 벤더와 무관하게 걸어 둔다.
        requireSafePathSegment(model);
        return "/api.openai.com/v1/responses";
    }

    @Override
    protected Map<String, String> authHeaders(String key) {
        return Map.of("Authorization", "Bearer " + key);
    }

    @Override
    protected Map<String, Object> requestBody(ChatRequest request) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "input_text", "text", request.instruction()));
        for (Attachment attachment : request.attachments()) {
            content.add(attachmentPart(attachment));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("max_output_tokens", properties.maxOutputTokens());
        body.put("input", List.of(Map.of("role", "user", "content", content)));
        if (request.responseSchema() != null) {
            body.put("text", Map.of("format", Map.of(
                    "type", "json_schema",
                    "name", request.responseSchema().name(),
                    "strict", true,
                    "schema", readSchema(request.responseSchema()))));
        }
        return body;
    }

    /**
     * 첨부 종류에 따라 자리가 갈린다 — 이미지는 {@code input_image}, 그 밖(PDF)은
     * {@code input_file} 이다. Gemini 가 {@code inline_data} 하나로 받는 것과 다른 점이고,
     * 기본 벤더를 Gemini 로 둔 실질적 이유이기도 하다.
     */
    private static Map<String, Object> attachmentPart(Attachment attachment) {
        String dataUri = dataUri(attachment.mediaType(), attachment.content());
        if (PDF_MEDIA_TYPE.equalsIgnoreCase(attachment.mediaType())) {
            // filename 은 필수다. 원본 파일명을 쓰지 않는다 — 사용자 입력을 외부로 흘리지 않는다.
            return Map.of("type", "input_file", "filename", "attachment.pdf", "file_data", dataUri);
        }
        return Map.of("type", "input_image", "image_url", dataUri);
    }

    private JsonNode readSchema(JsonSchema schema) {
        try {
            return objectMapper.readTree(schema.schemaJson());
        } catch (Exception e) {
            throw new IllegalArgumentException("responseSchema 가 올바른 JSON 이 아니다: " + schema.name(), e);
        }
    }

    @Override
    protected String responseText(JsonNode response) {
        for (JsonNode item : response.path("output")) {
            for (JsonNode part : item.path("content")) {
                if ("output_text".equals(part.path("type").asText()) && part.path("text").isTextual()) {
                    return part.path("text").asText();
                }
            }
        }
        return null;
    }

    @Override
    protected boolean isTruncated(JsonNode response) {
        return "max_output_tokens".equals(response.path("incomplete_details").path("reason").asText());
    }

    @Override
    protected Usage usage(JsonNode response) {
        JsonNode usage = response.path("usage");
        if (!usage.hasNonNull("input_tokens") || !usage.hasNonNull("output_tokens")) {
            return Usage.unknown();
        }
        return new Usage(usage.path("input_tokens").asInt(), usage.path("output_tokens").asInt());
    }

    @Override
    protected String respondedModel(JsonNode response) {
        JsonNode model = response.path("model");
        return model.isTextual() && !model.asText().isBlank() ? model.asText() : properties.model();
    }
}
