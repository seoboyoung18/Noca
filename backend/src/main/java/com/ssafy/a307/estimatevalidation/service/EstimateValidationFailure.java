package com.ssafy.a307.estimatevalidation.service;

/**
 * 파일 검증이 실패하는 사유 분류.
 *
 * <p><b>여기 있는 문구가 그대로 사용자에게 나간다</b>
 * ({@code estimate_validation.failure_reason} → {@code ValidationStatusResponse.failureReason}).
 * 그래서 두 가지를 지킨다.
 * <ul>
 *   <li><b>내부 정보를 담지 않는다.</b> 예외 메시지·스택·저장소 키·API 키·모델 이름을 넣지 않는다.
 *       실제 원인은 로그에만 남긴다</li>
 *   <li><b>다음에 뭘 하면 되는지 알려 준다.</b> "내부 오류" 하나로 뭉뚱그리면 사용자는
 *       다시 올려야 하는지 기다려야 하는지 알 수 없다</li>
 * </ul>
 *
 * <p>열이 {@code VARCHAR(200)} 이라 {@code EstimateValidation.fail} 이 길이를 한 번 더 자른다.
 */
public enum EstimateValidationFailure {

    /** 저장소에서 원본 파일을 가져오지 못했다. */
    STORAGE_READ("견적서 파일을 읽지 못했습니다. 다시 등록해 주세요."),

    /** 문서 판독 자체가 실패했다 — 모델 호출 실패·타임아웃·응답 형식 오류를 모두 포함한다. */
    EXTRACTION("견적서를 판독하지 못했습니다. 글자가 잘 보이는 사진이나 PDF로 다시 등록해 주세요."),

    /** 판독은 됐지만 쓸 수 있는 항목이 하나도 남지 않았다. */
    NO_ITEMS("견적서에서 항목을 찾지 못했습니다. 항목 표가 모두 보이도록 다시 촬영해 주세요."),

    /** 항목은 읽었지만 값이 규칙을 벗어나 검증에 넣을 수 없었다. */
    ITEM_MAPPING("견적서 항목을 해석하지 못했습니다. 직접 입력으로 등록해 주세요."),

    /**
     * 고른 판독 공급자가 이 파일 형식을 받지 못한다.
     *
     * <p>문구는 prompt35 7-1 이 정한 그대로다. 조용히 실패하거나 "항목을 찾지 못했다" 로
     * 둔갑시키지 않는다 — 사용자가 원인을 알아야 다른 형식으로 다시 올릴 수 있다.
     */
    UNSUPPORTED_FORMAT("PDF는 현재 공급자에서 지원되지 않습니다."),

    /** 위 어디에도 해당하지 않는 실패. 사용자가 할 수 있는 일이 없다. */
    INTERNAL("검증 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."),

    /** 처리 중이던 프로세스가 사라져 되살릴 수 없는 건. */
    ABANDONED("검증이 중단되었습니다. 다시 등록해 주세요.");

    private final String userMessage;

    EstimateValidationFailure(String userMessage) {
        this.userMessage = userMessage;
    }

    public String userMessage() {
        return userMessage;
    }
}
