package com.ssafy.a307.common.exception;

import com.ssafy.a307.accident.image.AccidentImageValidationException;
import com.ssafy.a307.admin.AdminErrorCode;
import com.ssafy.a307.admin.AdminOperationException;
import com.ssafy.a307.common.kakao.KakaoLocalException;
import com.ssafy.a307.common.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
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

    /**
     * 관리자 작업이 도메인 규칙에 막혔다. <b>사유를 {@code error.code} 로 그대로 내보낸다.</b>
     *
     * <p>중복·버전 충돌·참조 중은 전부 409 지만 FE 가 해야 할 일이 다르다 —
     * 중복은 입력을 고치고, 버전 충돌은 다시 읽어 재시도하고, 참조 중은 먼저 다른 것을 바꿔야 한다.
     * 상태 코드 하나로 뭉개면 그 셋을 한글 메시지로 구분하게 된다.
     */
    @ExceptionHandler(AdminOperationException.class)
    public ResponseEntity<ErrorResponse> handleAdminOperation(AdminOperationException e) {
        return ResponseEntity.status(e.code().status())
                .body(new ErrorResponse(new ErrorResponse.Error(e.code().name(), e.getMessage())));
    }

    /**
     * 낙관적 잠금 충돌 — 내가 읽은 뒤 남이 먼저 바꿨다.
     *
     * <p>Hibernate 가 {@code UPDATE ... WHERE version = ?} 로 0행을 만나 던진다. 여기서 잡지
     * 않으면 500 이 나가 FE 가 "서버 오류" 로 안내하는데, 실제로는 <b>다시 읽고 다시 저장하면
     * 되는</b> 상황이다.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException e) {
        return ResponseEntity.status(AdminErrorCode.VERSION_CONFLICT.status())
                .body(new ErrorResponse(new ErrorResponse.Error(
                        AdminErrorCode.VERSION_CONFLICT.name(),
                        "다른 관리자가 먼저 변경했습니다. 다시 조회한 뒤 시도해 주세요.")));
    }

    /**
     * 카카오 로컬 API 호출 실패.
     *
     * <p><b>분류를 다시 하지 않는다.</b> {@link com.ssafy.a307.common.kakao.KakaoLocalException}
     * 이 이미 어떤 {@link ErrorCode} 로 나갈지 들고 있다 — 카카오는 쿼터 초과도 400, 점검도
     * 400 으로 주기 때문에 상태 코드로는 구분할 수 없고, 그 판단은 본문 {@code code} 를 읽는
     * 전송 계층에서 한 번만 한다. 여기서 다시 해석하면 두 판단이 갈린다.
     *
     * <p>메시지는 전송 계층이 만든 사용자용 문구다. <b>카카오 원본 메시지도, 키도 들어 있지
     * 않다</b> — 그 보장은 {@code KakaoLocalClient} 가 한다.
     */
    @ExceptionHandler(KakaoLocalException.class)
    public ResponseEntity<ErrorResponse> handleKakaoLocal(KakaoLocalException e) {
        return toResponse(e.errorCode(), e.getMessage());
    }

    /**
     * DB 제약 위반 — UNIQUE·NOT NULL·FK.
     *
     * <p>이 핸들러가 없으면 아래의 {@code Exception} catch-all 이 잡아 <b>500</b> 을 낸다.
     * 그런데 여기 오는 대부분은 서버 잘못이 아니라 <b>경쟁 조건</b>이다 — 같은 값으로 두
     * 요청이 동시에 들어와 사전 {@code exists} 검사를 둘 다 통과한 뒤 INSERT 에서 한쪽이
     * 밀린 경우다. 밀린 쪽에게 "서버 오류" 라고 말하면 재시도해도 같은 결과가 나온다.
     *
     * <p><b>제약명과 SQL 원문은 응답에 싣지 않는다.</b> 스키마 내부 이름이 밖으로 새고,
     * FE 가 그것을 문자열 비교해 분기하기 시작하면 제약 이름을 못 바꾸게 된다.
     *
     * <p>어떤 값이 부딪혔는지 알 수 있는 경로는 서비스가 먼저 잡아 구체적인 코드로 바꾼다
     * (예: {@code AdminPartNameMappingService.saveNew}). 여기는 그러지 못한 나머지를 받는
     * 마지막 그물이다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("DB 제약 위반", e);
        return toResponse(ErrorCode.CONFLICT,
                "이미 존재하는 값이거나 다른 데이터가 참조하고 있습니다. 다시 조회한 뒤 시도해 주세요.");
    }

    /**
     * 필수 쿼리 파라미터 누락 — {@code ?rawName=} 없이 부른 경우.
     *
     * <p>스프링이 기본으로 400 을 주는 예외지만, 이 클래스에 {@code Exception} catch-all 이
     * 있어 <b>먼저 가로채 500 으로 만든다.</b> 부품명 매핑의 수정·삭제가 {@code rawName} 을
     * 쿼리 파라미터로 받으므로 실제로 닿는 경로다.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e) {
        return toResponse(ErrorCode.INVALID_REQUEST, e.getParameterName() + " 파라미터가 필요합니다.");
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
