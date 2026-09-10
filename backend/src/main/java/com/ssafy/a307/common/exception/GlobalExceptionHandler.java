package com.ssafy.a307.common.exception;

import com.ssafy.a307.accident.image.AccidentImageValidationException;
import com.ssafy.a307.common.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        return toResponse(e.getErrorCode(), e.getMessage());
    }

    /** {@code @Valid} 위반 — 연식 범위, modelId 누락, PATCH 의 modelId 포함 등 */
    /**
     * 사고 이미지 제약 위반 — <b>사유 이름을 {@code error.code} 로 그대로 내보낸다.</b>
     *
     * <p>예전에는 서비스가 이 예외를 {@code BusinessException(INVALID_REQUEST, ...)} 으로 바꿔
     * 던졌다. 그러면 "20장 초과"·"HEIC 미지원"·"각도 코드 오류"가 전부 {@code INVALID_REQUEST}
     * 하나가 되어, FE 가 이들을 구분하려면 <b>한글 메시지를 문자열 비교</b>해야 했다. 메시지는
     * 문구가 바뀌는 값이라 그런 코드는 조용히 깨진다.
     *
     * <p>코드가 {@code ErrorCode} 열거형 밖의 값이라는 점은 의도적이다 —
     * {@code RestAccessDeniedHandler} 가 {@code SIGNUP_REQUIRED} 를 같은 방식으로 내보내는
     * 선례가 있다. FE 는 {@code error.code} 를 {@code string} 으로 받아야 한다.
     *
     * <p>사유 목록은 {@link AccidentImageValidationException.Reason} 이고 전부 400 이다.
     * 이 중 <b>{@code SERVER_CONVERSION_UNSUPPORTED} 가 HEIC·HEIF 거절</b>이며,
     * presigned URL 을 발급하기 전에 걸린다.
     *
     * <p>완료 통보 경로는 여기 오지 않는다. 그쪽은 한 장의 실패가 전체를 뒤집으면 안 되므로
     * 200 응답의 {@code results[].failureCode} 로 같은 이름을 싣는다.
     */
    @ExceptionHandler(AccidentImageValidationException.class)
    public ResponseEntity<ErrorResponse> handleAccidentImageValidation(AccidentImageValidationException e) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(new ErrorResponse(new ErrorResponse.Error(e.reason().name(), e.getMessage())));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .collect(Collectors.joining(", "));
        return toResponse(ErrorCode.INVALID_REQUEST, message);
    }

    /** 본문이 JSON 이 아니거나 타입이 맞지 않는 경우 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
        return toResponse(ErrorCode.INVALID_REQUEST, "요청 본문을 해석할 수 없습니다.");
    }

    /** {@code /api/vehicles/abc} 처럼 경로 변수 타입이 맞지 않는 경우 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return toResponse(ErrorCode.INVALID_REQUEST, e.getName() + " 값이 올바르지 않습니다.");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return toResponse(ErrorCode.INVALID_REQUEST, "견적서 파일은 10MB 이하여야 합니다.");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestPart(MissingServletRequestPartException e) {
        return toResponse(ErrorCode.INVALID_REQUEST, e.getRequestPartName() + " 파트가 필요합니다.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return toResponse(ErrorCode.INTERNAL_ERROR, "서버 내부 오류가 발생했습니다.");
    }

    private String describe(FieldError fieldError) {
        return fieldError.getField() + ": " + fieldError.getDefaultMessage();
    }

    private ResponseEntity<ErrorResponse> toResponse(ErrorCode errorCode, String message) {
        return ResponseEntity.status(errorCode.getStatus())
                .body(ErrorResponse.of(errorCode, message));
    }
}
