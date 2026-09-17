package com.ssafy.a307.common.llm;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses API 또는 Chat Completions API 를 GMS 프록시로 부른다.
 *
 * <pre>
 * POST {base}/api.openai.com/v1/responses
 * POST {base}/api.openai.com/v1/chat/completions
 * Authorization: Bearer {GMS_KEY}
 * </pre>
 *
 * <p>어느 쪽을 쓸지는 {@code app.gms.openai-api} 가 정한다. 기본값은 기존 동작을 보존하는
 * {@code responses} 고, GMS 의 gpt-5.4 안내대로 부를 때만 {@code chat} 으로 바꾼다.
 * <b>소비처는 바뀌지 않는다</b> — 구조화 출력은 두 형식 모두 {@code strict: true} 로 싣고,
 * 지금 이 계층을 쓰는 세 소비처는 첨부 없는 텍스트 요청뿐이다.
 * 첨부(특히 PDF)는 {@link #chatAttachmentPart} 의 주의를 먼저 읽는다.
 *
 * <p><b>견적서 PDF 판독은 이 클래스를 지나가지 않는다.</b> 그쪽은
 * {@code app.estimate-ocr.*} 와 {@code LlmVendors} 를 쓰는 별개 경로다. 그래서
 * {@code GMS_PROVIDER}·{@code GMS_MODEL}·{@code GMS_OPENAI_API} 를 바꿔도 OCR 은 영향받지 않는다.
 *
 * <p><b>모델명이 경로에 들어가지 않는다.</b> 본문 필드다. Gemini 와 다른 점이다.
 */
class OpenAiGmsClient extends GmsChatClientSupport {

    private static final String PDF_MEDIA_TYPE = "application/pdf";
    private static final String CHAT_USER_REQUEST = "Return the result requested by the developer message.";

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
        return usesChatCompletions()
                ? "/api.openai.com/v1/chat/completions"
                : "/api.openai.com/v1/responses";
    }

    @Override
    protected Map<String, String> authHeaders(String key) {
        return Map.of("Authorization", "Bearer " + key);
    }

    @Override
    protected Map<String, Object> requestBody(ChatRequest request) {
        return usesChatCompletions() ? chatRequestBody(request) : responsesRequestBody(request);
    }

    private Map<String, Object> responsesRequestBody(ChatRequest request) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(Map.of("type", "input_text", "text", request.instruction()));
        for (Attachment attachment : request.attachments()) {
            content.add(responsesAttachmentPart(attachment));
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

    private Map<String, Object> chatRequestBody(ChatRequest request) {
        List<Map<String, Object>> userContent = new ArrayList<>();
        userContent.add(Map.of("type", "text", "text", CHAT_USER_REQUEST));
        for (Attachment attachment : request.attachments()) {
            userContent.add(chatAttachmentPart(attachment));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.model());
        body.put("messages", List.of(
                Map.of("role", "developer", "content", request.instruction()),
                Map.of("role", "user", "content", userContent)));
        body.put("max_completion_tokens", properties.maxOutputTokens());
        if (request.responseSchema() != null) {
            body.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
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
    private static Map<String, Object> responsesAttachmentPart(Attachment attachment) {
        String dataUri = dataUri(attachment.mediaType(), attachment.content());
        if (PDF_MEDIA_TYPE.equalsIgnoreCase(attachment.mediaType())) {
            // filename 은 필수다. 원본 파일명을 쓰지 않는다 — 사용자 입력을 외부로 흘리지 않는다.
            return Map.of("type", "input_file", "filename", "attachment.pdf", "file_data", dataUri);
        }
        return Map.of("type", "input_image", "image_url", dataUri);
    }

    /**
     * Chat Completions 는 이미지와 파일의 content part 모양이 서로 다르다.
     *
     * <p><b>PDF 쪽은 검증하지 못했다.</b> GMS 키 없이는 프록시가 401 에서 끊어 확인할 방법이
     * 없었고(answer86 참고), 같은 저장소의 OCR 벤더
     * {@code LlmVendors.OpenAi} 는 {@code supportsPdf() == false} 라고 반대로 적어 둔다.
     * 실제 PDF 판독은 그쪽 경로({@code app.estimate-ocr.*})가 하고 <b>이 계층에 첨부를 넘기는
     * 소비처는 아직 없다</b> — 셋 다 {@code ChatRequest.textOnly} 다. 첨부를 처음 넘기게 될 때
     * 실제 호출로 확인하고, 거절해야 한다면 그때 명시적으로 막는다.
     */
    private static Map<String, Object> chatAttachmentPart(Attachment attachment) {
        String dataUri = dataUri(attachment.mediaType(), attachment.content());
        if (PDF_MEDIA_TYPE.equalsIgnoreCase(attachment.mediaType())) {
            return Map.of(
                    "type", "file",
                    "file", Map.of("filename", "attachment.pdf", "file_data", dataUri));
        }
        return Map.of("type", "image_url", "image_url", Map.of("url", dataUri));
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
        if (usesChatCompletions()) {
            JsonNode content = response.path("choices").path(0).path("message").path("content");
            return content.isTextual() ? content.asText() : null;
        }
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
        if (usesChatCompletions()) {
            return "length".equals(response.path("choices").path(0).path("finish_reason").asText());
        }
        return "max_output_tokens".equals(response.path("incomplete_details").path("reason").asText());
    }

    @Override
    protected Usage usage(JsonNode response) {
        JsonNode usage = response.path("usage");
        String inputField = usesChatCompletions() ? "prompt_tokens" : "input_tokens";
        String outputField = usesChatCompletions() ? "completion_tokens" : "output_tokens";
        if (!usage.hasNonNull(inputField) || !usage.hasNonNull(outputField)) {
            return Usage.unknown();
        }
        return new Usage(usage.path(inputField).asInt(), usage.path(outputField).asInt());
    }

    @Override
    protected String respondedModel(JsonNode response) {
        JsonNode model = response.path("model");
        return model.isTextual() && !model.asText().isBlank() ? model.asText() : properties.model();
    }

    private boolean usesChatCompletions() {
        return properties.openAiApi() == OpenAiApi.CHAT;
    }
}
