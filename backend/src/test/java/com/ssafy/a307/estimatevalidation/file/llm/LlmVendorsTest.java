package com.ssafy.a307.estimatevalidation.file.llm;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 벤더별 전송 형식. <b>HTTP 를 타지 않는다</b> — 만들어진 경로·헤더·본문 모양만 본다.
 *
 * <p>이 테스트가 있는 이유는 <b>인증 헤더 이름이 벤더마다 다르기 때문</b>이다.
 * OpenAI 는 {@code Authorization: Bearer}, Gemini 는 {@code x-goog-api-key} 다. URL 만 갈아끼우고
 * 헤더를 그대로 두면 벤더를 바꾸는 순간 401 이고, 그때는 원인을 찾기 어렵다.
 */
@DisplayName("GMS 벤더 전송 형식")
class LlmVendorsTest {

    private static final String KEY = "test-key-not-a-real-credential";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("벤더는 gemini 와 openai 둘뿐이다 — claude 는 만들지 않았다")
    void onlyTwoVendorsExist() {
        assertThat(LlmVendors.all()).extracting(LlmVendor::name)
                .containsExactly("gemini", "openai");
    }

    @Test
    @DisplayName("Gemini 경로에 벤더 호스트와 모델 이름이 들어간다")
    void geminiPathCarriesHostAndModel() {
        LlmVendor gemini = vendor("gemini");

        assertThat(gemini.path("gemini-2.5-flash"))
                .isEqualTo("/generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent");
        assertThat(LlmVendors.GMS_BASE_URL + gemini.path("gemini-2.5-flash"))
                .isEqualTo("https://gms.ssafy.io/gmsapi"
                        + "/generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent");
    }

    @Test
    @DisplayName("OpenAI 경로에 벤더 호스트가 들어가고 모델은 본문에만 있다")
    void openAiPathCarriesHostOnly() {
        LlmVendor openai = vendor("openai");

        assertThat(openai.path("gpt-4o")).isEqualTo("/api.openai.com/v1/chat/completions");
        assertThat(openai.body("gpt-4o", "지시문", "image/png", "AAA", 4096))
                .containsEntry("model", "gpt-4o");
    }

    @Test
    @DisplayName("인증 헤더 이름이 벤더마다 다르다")
    void authHeadersDifferPerVendor() {
        assertThat(vendor("gemini").headers(KEY))
                .isEqualTo(Map.of("x-goog-api-key", KEY));
        assertThat(vendor("openai").headers(KEY))
                .isEqualTo(Map.of("Authorization", "Bearer " + KEY));
    }

    @Test
    @DisplayName("Gemini 만 PDF 를 직접 받는다")
    void onlyGeminiTakesPdf() {
        assertThat(vendor("gemini").supportsPdf()).isTrue();
        assertThat(vendor("openai").supportsPdf()).isFalse();
    }

    @Test
    @DisplayName("Gemini 본문은 inline_data 로 mime 타입과 base64 를 함께 싣는다")
    void geminiBodyUsesInlineData() throws Exception {
        String body = objectMapper.writeValueAsString(
                vendor("gemini").body("gemini-2.5-flash", "지시문", "application/pdf", "QkFTRTY0", 4096));

        assertThat(body).contains("\"inline_data\"")
                .contains("\"mime_type\":\"application/pdf\"")
                .contains("\"data\":\"QkFTRTY0\"")
                .contains("지시문");
        // 스트리밍을 쓰지 않는다 — SSE 파서를 만들 이유가 없다.
        assertThat(body).doesNotContain("stream");
    }

    @Test
    @DisplayName("OpenAI 본문은 data URI 로 이미지를 싣는다")
    void openAiBodyUsesDataUri() throws Exception {
        String body = objectMapper.writeValueAsString(
                vendor("openai").body("gpt-4o", "지시문", "image/jpeg", "QkFTRTY0", 4096));

        assertThat(body).contains("data:image/jpeg;base64,QkFTRTY0").contains("image_url");
        assertThat(body).doesNotContain("stream");
    }

    @Test
    @DisplayName("응답에서 본문 텍스트를 꺼낸다. 못 꺼내면 null 이다")
    void extractsTextOrNull() throws Exception {
        var geminiOk = objectMapper.readTree("""
                {"candidates":[{"content":{"parts":[{"text":"{\\"items\\":[]}"}]}}]}
                """);
        var openAiOk = objectMapper.readTree("""
                {"choices":[{"message":{"content":"{\\"items\\":[]}"}}]}
                """);

        assertThat(vendor("gemini").extractText(geminiOk)).isEqualTo("{\"items\":[]}");
        assertThat(vendor("openai").extractText(openAiOk)).isEqualTo("{\"items\":[]}");
        assertThat(vendor("gemini").extractText(objectMapper.readTree("{}"))).isNull();
        assertThat(vendor("openai").extractText(objectMapper.readTree("{}"))).isNull();
    }

    private static LlmVendor vendor(String name) {
        List<LlmVendor> vendors = LlmVendors.all();
        return vendors.stream().filter(candidate -> candidate.name().equals(name)).findFirst().orElseThrow();
    }
}
