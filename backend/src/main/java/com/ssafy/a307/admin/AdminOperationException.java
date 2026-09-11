package com.ssafy.a307.admin;

/**
 * 관리자 작업이 도메인 규칙에 막혔다. {@link AdminErrorCode} 가 HTTP 상태와
 * {@code error.code} 를 함께 정한다.
 *
 * <p>{@code BusinessException} 을 쓰지 않는 이유는 {@link AdminErrorCode} Javadoc 에 있다 —
 * 사유를 상태 코드 8개로 뭉개지 않기 위해서다.
 */
public class AdminOperationException extends RuntimeException {

    private final AdminErrorCode code;

    public AdminOperationException(AdminErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    public AdminErrorCode code() {
        return code;
    }

    public static AdminOperationException notFound(String what) {
        return new AdminOperationException(AdminErrorCode.ADMIN_TARGET_NOT_FOUND, what + "을(를) 찾을 수 없습니다.");
    }
}
