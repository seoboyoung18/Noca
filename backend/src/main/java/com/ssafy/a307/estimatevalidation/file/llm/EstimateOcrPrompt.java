package com.ssafy.a307.estimatevalidation.file.llm;

/**
 * 판독 모델에 보낼 지시문.
 *
 * <p><b>코드에 이미 있는 제약을 그대로 옮겨 적었다.</b> 여기 적힌 규칙과
 * {@code WorkTypeMapper}·{@code ManualValidationItemRequest}·{@code EstimateOcrPort.OcrLineItem} 의
 * 제약이 어긋나면 모델이 성실히 답해도 어댑터가 항목을 버린다. 둘 중 하나를 고칠 때는
 * 반드시 같이 고친다.
 *
 * <p><b>지어내지 말라고 못 박는다.</b> 이 저장소는 {@code difference()}·{@code within()} 이
 * null 을 0·false 로 바꾸지 않는 곳이다. 읽지 못한 금액을 그럴듯하게 채우면 그 값이 그대로
 * 정비소 견적 총액이 되고 AI 중앙값과 비교돼 사용자에게 등급으로 나간다.
 */
public final class EstimateOcrPrompt {

    /**
     * 지시문. 모델이 <b>JSON 하나만</b> 내도록 강제한다.
     *
     * <p>작업유형 6종은 {@code WorkTypeMapper.BY_NAME} 의 한국어 키와 정확히 같다.
     * 상한값은 {@code ManualValidationItemRequest} 의 애너테이션과 같다.
     */
    public static final String INSTRUCTION = """
            당신은 자동차 정비 견적서를 읽어 항목표를 구조화하는 도구입니다.
            첨부된 견적서에서 수리 항목을 읽어 아래 JSON 형식으로만 답하세요.
            설명, 마크다운 코드펜스, 그 밖의 어떤 텍스트도 덧붙이지 마세요.

            {
              "items": [
                {
                  "lineNo": 1,
                  "rawItemName": "프론트 펜더",
                  "workType": "판금",
                  "quantity": 1,
                  "partCost": 120000,
                  "laborCost": 30000,
                  "confidence": 0.92
                }
              ],
              "documentTotal": 150000,
              "documentConfidence": 0.9
            }

            규칙:
            - workType 은 반드시 다음 6개 중 하나입니다: 교환, 탈착, 판금, 도장, 오버홀, 수리.
              견적서에 다른 말로 적혀 있으면 뜻이 가장 가까운 것으로 바꾸고, 6개 중 어느 것으로도
              볼 수 없으면 그 항목을 빼세요.
            - lineNo 는 1 이상 32767 이하의 정수이고 견적서 안에서 중복될 수 없습니다.
              견적서에 번호가 없으면 위에서부터 1, 2, 3 으로 매기세요.
            - rawItemName 은 견적서에 적힌 품명을 그대로 적습니다. 비울 수 없고 200자 이하입니다.
            - quantity 는 1 이상 32767 이하의 정수입니다. 적혀 있지 않으면 1로 봅니다.
            - partCost(부품비)와 laborCost(공임)는 0 이상의 정수이고 원 단위입니다.
              쉼표와 '원' 을 빼고 숫자만 적으세요. 한 항목에서 두 값이 모두 0일 수는 없습니다.
            - 부품비와 공임이 한 칸에 합쳐져 있으면 partCost 에 넣고 laborCost 는 0으로 둡니다.
              둘을 임의로 나누지 마세요.
            - confidence 는 그 항목을 얼마나 확실히 읽었는지 0.0~1.0 으로 적습니다.
            - 항목은 최대 200개까지입니다.

            매우 중요 — 값을 지어내지 마세요:
            - 흐리거나 가려져서 읽지 못한 금액은 절대 추정하지 마세요. 그 항목을 빼거나
              confidence 를 0.3 미만으로 낮추세요.
            - 견적서에 없는 항목을 추가하지 마세요.
            - 합계, 부가세, 할인, 면책금 같은 요약 줄은 items 에 넣지 마세요.
              문서에 적힌 총액은 documentTotal 에만 적습니다. 읽지 못했으면 null 로 두세요.
            - 견적서가 아니거나 항목표를 찾을 수 없으면 items 를 빈 배열로 두세요.
            """;

    private EstimateOcrPrompt() {
    }
}
