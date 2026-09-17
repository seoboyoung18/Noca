package com.ssafy.a307.common.llm;

import com.ssafy.a307.estimatevalidation.file.pdf.ReportNarrativeGenerator;
import com.ssafy.a307.repairchecklist.service.RepairChecklistGenerator;
import com.ssafy.a307.repairquestion.service.RepairQuestionGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/** OpenAI {@code strict: true} 가 요구하는 소비처 JSON Schema 계약. */
@DisplayName("OpenAI 구조화 출력 스키마")
class StructuredOutputSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("체크리스트 스키마는 모든 object 를 닫고 모든 필드를 required 로 둔다")
    void checklistSchemaIsStrict() throws Exception {
        JsonNode schema = schemaOf(RepairChecklistGenerator.class);

        assertClosedObject(schema, "items");
        assertClosedObject(schema.at("/properties/items/items"), "content");
    }

    @Test
    @DisplayName("질문 스키마는 선택 부품 코드를 nullable required 로 표현한다")
    void questionSchemaIsStrict() throws Exception {
        JsonNode schema = schemaOf(RepairQuestionGenerator.class);
        JsonNode item = schema.at("/properties/questions/items");

        assertClosedObject(schema, "questions");
        assertClosedObject(item, "content", "partCode");
        assertThat(item.at("/properties/partCode/type").toString())
                .isEqualTo("[\"string\",\"null\"]");
    }

    @Test
    @DisplayName("PDF 문장 스키마는 빈 itemNotes 도 명시하도록 강제한다")
    void reportNarrativeSchemaIsStrict() throws Exception {
        JsonNode schema = schemaOf(ReportNarrativeGenerator.class);

        assertClosedObject(schema, "gradeExplanation", "itemNotes");
        assertClosedObject(schema.at("/properties/itemNotes/items"), "lineNo", "note");
    }

    private JsonNode schemaOf(Class<?> owner) throws Exception {
        Field field = owner.getDeclaredField("RESPONSE_SCHEMA");
        assertThat(field.trySetAccessible()).isTrue();
        return objectMapper.readTree((String) field.get(null));
    }

    private static void assertClosedObject(JsonNode schema, String... required) {
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("required")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(required);
    }
}
