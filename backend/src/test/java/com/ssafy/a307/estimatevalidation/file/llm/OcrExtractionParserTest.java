package com.ssafy.a307.estimatevalidation.file.llm;

import tools.jackson.databind.ObjectMapper;
import com.ssafy.a307.estimatevalidation.file.EstimateOcrPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 판독 응답 해석. <b>응답 JSON 을 고정 문자열로 주입해</b> 파싱·검증·항목 폐기만 본다 —
 * HTTP 를 타지 않고 LLM 을 실제로 부르지 않는다. 네트워크·비용·비결정성이 붙으면 CI 가 흔들린다.
 *
 * <p>여기서 지키려는 성질은 하나다. <b>모델이 한 줄을 잘못 내도 견적서 전체가 죽지 않는다.</b>
 * 동시에 <b>잘못된 값이 조용히 통과하지도 않는다</b> — 버린 항목은 버린 것으로 남는다.
 */
@DisplayName("판독 응답 해석")
class OcrExtractionParserTest {

    private final OcrExtractionParser parser = new OcrExtractionParser(new ObjectMapper(), 0.3);

    @Test
    @DisplayName("정상 응답을 항목으로 바꾼다")
    void parsesWellFormedResponse() {
        EstimateOcrPort.OcrExtraction result = parser.parse("""
                {
                  "items": [
                    {"lineNo":1,"rawItemName":"프론트 펜더","workType":"판금",
                     "quantity":1,"partCost":120000,"laborCost":30000,"confidence":0.92},
                    {"lineNo":2,"rawItemName":"프론트 도어","workType":"교환",
                     "quantity":2,"partCost":200000,"laborCost":0,"confidence":0.81}
                  ],
                  "documentTotal": 550000,
                  "documentConfidence": 0.88
                }
                """);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().getFirst().rawItemName()).isEqualTo("프론트 펜더");
        assertThat(result.items().getFirst().workType()).isEqualTo("판금");
        assertThat(result.items().get(1).quantity()).isEqualTo(2);
        assertThat(result.claimedTotal()).isEqualTo(550_000L);
        assertThat(result.documentConfidence()).isEqualTo(0.88);
    }

    @Test
    @DisplayName("코드펜스와 앞뒤 설명이 붙어 와도 읽는다")
    void stripsCodeFenceAndProse() {
        EstimateOcrPort.OcrExtraction result = parser.parse("""
                견적서를 읽었습니다.
                ```json
                {"items":[{"lineNo":1,"rawItemName":"범퍼","workType":"도장",
                           "quantity":1,"partCost":0,"laborCost":80000,"confidence":0.7}]}
                ```
                도움이 되었길 바랍니다.
                """);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().laborCost()).isEqualTo(80_000);
    }

    @Nested
    @DisplayName("항목 단위로 버리고 계속한다")
    class PartialDiscard {

        @Test
        @DisplayName("규칙을 어긴 항목만 빠지고 나머지는 살아남는다")
        void keepsValidItemsOnly() {
            EstimateOcrPort.OcrExtraction result = parser.parse("""
                    {"items":[
                      {"lineNo":1,"rawItemName":"프론트 펜더","workType":"판금",
                       "quantity":1,"partCost":120000,"laborCost":30000,"confidence":0.9},
                      {"lineNo":2,"rawItemName":"","workType":"판금",
                       "quantity":1,"partCost":10000,"laborCost":0,"confidence":0.9},
                      {"lineNo":3,"rawItemName":"범퍼","workType":"광택",
                       "quantity":1,"partCost":10000,"laborCost":0,"confidence":0.9},
                      {"lineNo":4,"rawItemName":"휠","workType":"교환",
                       "quantity":1,"partCost":-500,"laborCost":0,"confidence":0.9},
                      {"lineNo":5,"rawItemName":"도어","workType":"교환",
                       "quantity":1,"partCost":0,"laborCost":0,"confidence":0.9},
                      {"lineNo":6,"rawItemName":"트렁크","workType":"수리",
                       "quantity":0,"partCost":10000,"laborCost":0,"confidence":0.9},
                      {"lineNo":7,"rawItemName":"후드","workType":"탈착",
                       "quantity":1,"partCost":40000,"laborCost":10000,"confidence":0.95}
                    ]}
                    """);

            // 살아남는 것은 1번과 7번뿐이다.
            assertThat(result.items()).extracting("lineNo").containsExactly(1, 7);
        }

        @Test
        @DisplayName("신뢰도가 임계값 미만인 항목은 버린다")
        void discardsLowConfidence() {
            EstimateOcrPort.OcrExtraction result = parser.parse("""
                    {"items":[
                      {"lineNo":1,"rawItemName":"범퍼","workType":"도장",
                       "quantity":1,"partCost":50000,"laborCost":0,"confidence":0.29},
                      {"lineNo":2,"rawItemName":"펜더","workType":"판금",
                       "quantity":1,"partCost":50000,"laborCost":0,"confidence":0.30}
                    ]}
                    """);

            assertThat(result.items()).extracting("lineNo").containsExactly(2);
        }

        @Test
        @DisplayName("행번호가 중복되면 뒤에 온 항목을 버린다")
        void discardsDuplicateLineNumbers() {
            EstimateOcrPort.OcrExtraction result = parser.parse("""
                    {"items":[
                      {"lineNo":1,"rawItemName":"범퍼","workType":"도장",
                       "quantity":1,"partCost":50000,"laborCost":0,"confidence":0.9},
                      {"lineNo":1,"rawItemName":"범퍼(중복)","workType":"도장",
                       "quantity":1,"partCost":70000,"laborCost":0,"confidence":0.9}
                    ]}
                    """);

            assertThat(result.items()).hasSize(1);
            assertThat(result.items().getFirst().rawItemName()).isEqualTo("범퍼");
        }

        @Test
        @DisplayName("소계가 넘치는 항목은 버린다 — 뒤에서 터지게 두지 않는다")
        void discardsOverflowingSubtotal() {
            EstimateOcrPort.OcrExtraction result = parser.parse("""
                    {"items":[
                      {"lineNo":1,"rawItemName":"과금 폭탄","workType":"교환",
                       "quantity":32767,"partCost":2147483647,"laborCost":2147483647,"confidence":0.9}
                    ]}
                    """);

            assertThat(result.items()).isEmpty();
        }

        @Test
        @DisplayName("항목이 하나도 안 남으면 빈 결과를 돌려준다 — 실패 판단은 워커가 한다")
        void emptyResultIsReturnedNotThrown() {
            EstimateOcrPort.OcrExtraction result = parser.parse("""
                    {"items":[{"lineNo":1,"rawItemName":"범퍼","workType":"광택",
                               "quantity":1,"partCost":10000,"laborCost":0,"confidence":0.9}]}
                    """);

            assertThat(result.items()).isEmpty();
        }
    }

    @Nested
    @DisplayName("지어낸 값을 걸러 낸다")
    class NoFabrication {

        @Test
        @DisplayName("작업유형은 직접 입력과 같은 매퍼로 본다 — 6종 밖은 통과하지 못한다")
        void workTypeUsesTheSameMapper() {
            EstimateOcrPort.OcrExtraction result = parser.parse("""
                    {"items":[
                      {"lineNo":1,"rawItemName":"a","workType":"교환","quantity":1,
                       "partCost":1000,"laborCost":0,"confidence":0.9},
                      {"lineNo":2,"rawItemName":"b","workType":"탈착","quantity":1,
                       "partCost":1000,"laborCost":0,"confidence":0.9},
                      {"lineNo":3,"rawItemName":"c","workType":"판금","quantity":1,
                       "partCost":1000,"laborCost":0,"confidence":0.9},
                      {"lineNo":4,"rawItemName":"d","workType":"도장","quantity":1,
                       "partCost":1000,"laborCost":0,"confidence":0.9},
                      {"lineNo":5,"rawItemName":"e","workType":"오버홀","quantity":1,
                       "partCost":1000,"laborCost":0,"confidence":0.9},
                      {"lineNo":6,"rawItemName":"f","workType":"수리","quantity":1,
                       "partCost":1000,"laborCost":0,"confidence":0.9},
                      {"lineNo":7,"rawItemName":"g","workType":"세차","quantity":1,
                       "partCost":1000,"laborCost":0,"confidence":0.9}
                    ]}
                    """);

            assertThat(result.items()).extracting("lineNo").containsExactly(1, 2, 3, 4, 5, 6);
        }

        @Test
        @DisplayName("문서상 총액이 없거나 0 이하면 null 이다 — 0으로 바꾸지 않는다")
        void missingDocumentTotalStaysNull() {
            assertThat(parser.parse("{\"items\":[]}").claimedTotal()).isNull();
            assertThat(parser.parse("{\"items\":[],\"documentTotal\":0}").claimedTotal()).isNull();
            assertThat(parser.parse("{\"items\":[],\"documentTotal\":null}").claimedTotal()).isNull();
        }
    }

    @Nested
    @DisplayName("응답 자체가 잘못된 경우")
    class BrokenResponse {

        @Test
        @DisplayName("JSON 이 아니면 판독 실패로 던진다")
        void nonJsonFails() {
            assertThatThrownBy(() -> parser.parse("견적서를 읽을 수 없습니다."))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("items 배열이 없으면 판독 실패로 던진다")
        void missingItemsArrayFails() {
            assertThatThrownBy(() -> parser.parse("{\"documentTotal\":1000}"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("빈 응답은 판독 실패다")
        void blankFails() {
            assertThatThrownBy(() -> parser.parse("  "))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> parser.parse(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("실패 메시지에 응답 본문을 담지 않는다 — 견적서 내용이 로그로 샌다")
        void failureMessageHasNoResponseBody() {
            String body = "차주 홍길동 12가3456 010-1234-5678";

            assertThatThrownBy(() -> parser.parse(body))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("홍길동")
                    .hasMessageNotContaining("010-1234");
        }
    }
}
