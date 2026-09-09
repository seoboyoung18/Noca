package com.ssafy.a307.common.llm;

import java.util.List;
import java.util.Objects;

/**
 * LLM 한 번 호출. <b>도메인을 전혀 모르는 전송 경계다.</b>
 *
 * <p>이 인터페이스에는 "견적서" 라는 단어가 나오지 않는다. 지시문도, 기대 스키마도 <b>소비처가
 * 넘긴다.</b> 그래야 소비처가 둘(항목 추출 · 요약)이고 벤더가 둘일 때 인증·타임아웃·재시도·오류
 * 매핑·크레딧 가드가 네 벌로 복제되지 않는다.
 *
 * <p>반대로 포트 시그니처에 <b>벤더 흔적이 새어 나와서도 안 된다.</b>
 * {@code response_format}·{@code contents}·{@code parts} 같은 이름이 여기 보이면 벤더를 바꿀 때
 * 소비처까지 고쳐야 한다. 벤더별 매핑은 각 구현 안에만 둔다.
 *
 * <p><b>구현 빈은 키가 있을 때만 뜬다</b>({@link GmsKeyPresentCondition}). 소비처는
 * {@code Optional<LlmChatPort>} 로 받아 없으면 503 을 주는 이 저장소의 기존 관례를 따르면 된다.
 */
public interface LlmChatPort {

    /**
     * @throws LlmChatException 전송·응답 처리 실패. 재시도 가능 여부를 담고 있다
     */
    ChatResult complete(ChatRequest request);

    /**
     * @param instruction    모델에 보낼 지시문. 도메인 지식은 전부 여기 담겨 온다
     * @param attachments    이미지·PDF 첨부. 없으면 빈 목록
     * @param responseSchema 기대하는 JSON 스키마. null 이면 구조화 출력을 요청하지 않는다
     */
    record ChatRequest(String instruction, List<Attachment> attachments, JsonSchema responseSchema) {

        public ChatRequest {
            if (instruction == null || instruction.isBlank()) {
                throw new IllegalArgumentException("instruction is required");
            }
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        public static ChatRequest textOnly(String instruction, JsonSchema responseSchema) {
            return new ChatRequest(instruction, List.of(), responseSchema);
        }
    }

    /**
     * 첨부 하나.
     *
     * <p><b>{@code toString} 이 바이트를 내지 않는다.</b> record 기본 구현은 배열의 식별자만 찍지만
     * 그마저도 의미가 없고, 무엇보다 이 자리에 파일 내용이 오는 실수를 원천에서 막아 둔다.
     * 첨부에는 차주 이름·차량번호가 찍힌 견적서가 들어올 수 있다.
     */
    record Attachment(String mediaType, byte[] content) {

        public Attachment {
            if (mediaType == null || mediaType.isBlank()) {
                throw new IllegalArgumentException("mediaType is required");
            }
            content = content == null ? null : content.clone();
            Objects.requireNonNull(content, "content");
            if (content.length == 0) {
                throw new IllegalArgumentException("content must not be empty");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        public int size() {
            return content.length;
        }

        @Override
        public String toString() {
            return "Attachment[mediaType=%s, bytes=%d]".formatted(mediaType, content.length);
        }
    }

    /**
     * 구조화 출력으로 요구할 스키마.
     *
     * @param name       스키마 이름. OpenAI 가 요구한다
     * @param schemaJson JSON Schema 본문. <b>이 계층은 내용을 해석하지 않고</b> 벤더가 요구하는
     *                   자리에 그대로 실어 보낸다
     */
    record JsonSchema(String name, String schemaJson) {

        public JsonSchema {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("schema name is required");
            }
            if (schemaJson == null || schemaJson.isBlank()) {
                throw new IllegalArgumentException("schemaJson is required");
            }
        }
    }

    /**
     * @param json      모델이 낸 JSON 문자열. 코드펜스는 이미 벗겨져 있고 파싱도 한 번 통과했다
     * @param model     실제로 응답한 모델 이름. 소비처가 {@code llm_model} 에 남길 수 있게 담는다
     *                  (answer33 R-16 이 지적한 "항상 null" 을 채울 값이다)
     * @param usage     토큰 사용량. 크레딧 소비를 기록·추적할 유일한 단서다
     */
    record ChatResult(String json, String model, Usage usage) {

        public ChatResult {
            if (json == null || json.isBlank()) {
                throw new IllegalArgumentException("json is required");
            }
            usage = usage == null ? Usage.unknown() : usage;
        }
    }

    /** 토큰 사용량. 벤더가 주지 않으면 {@code -1} 로 둔다 — <b>0 으로 지어내지 않는다.</b> */
    record Usage(int inputTokens, int outputTokens) {

        public static Usage unknown() {
            return new Usage(-1, -1);
        }

        public boolean isKnown() {
            return inputTokens >= 0 && outputTokens >= 0;
        }
    }
}
