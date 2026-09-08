package com.ssafy.a307.accident.image;

/**
 * 사고 이미지 제약 검증 실패. {@code EstimateFileValidationException} 과 같은 관례다 —
 * 사유를 enum 으로 분류하고 메시지에는 위반한 제약과 실제 값을 담는다.
 * <p>
 * 이 예외는 도메인 경계 안쪽 신호다. HTTP 로 나갈 때는 서비스가
 * {@code BusinessException(INVALID_REQUEST, ...)} 으로 바꾸거나, 완료 통보처럼 부분 실패를
 * 허용하는 흐름에서는 이미지별 실패 항목으로 옮긴다.
 */
public final class AccidentImageValidationException extends IllegalArgumentException {

    private final Reason reason;

    public AccidentImageValidationException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        /** 파일이 없거나 0바이트. */
        MISSING_FILE,
        /** 장당 상한 초과. */
        FILE_TOO_LARGE,
        /** 파일명 규칙 위반 — 255자 초과·경로 구분자·제어문자·확장자 없음. */
        INVALID_FILE_NAME,
        /** JPG·PNG·HEIC 가 아닌 확장자. */
        UNSUPPORTED_EXTENSION,
        /** 확장자와 Content-Type 불일치. */
        UNSUPPORTED_CONTENT_TYPE,
        /** 매직바이트가 확장자와 불일치. 확장자만 바꿔 올린 파일이 여기서 걸린다. */
        SIGNATURE_MISMATCH,
        /**
         * 형식은 지원 목록에 있지만 <b>서버가 디코딩할 수 없다</b> — 현재는 HEIC 가 유일하다.
         * 클라이언트 변환 안내로 폴백한다(요구사항 21행 확인·결정 사항).
         */
        SERVER_CONVERSION_UNSUPPORTED,
        /** 사고 1건의 장수 상한 초과. 이미 등록된 수 + 요청 수로 판정한다. */
        TOO_MANY_IMAGES,
        /** 신고한 크기와 저장소의 실제 크기가 다르다. 신고값을 신뢰하지 않는다는 뜻이다. */
        SIZE_MISMATCH,
        /** 촬영 가이드가 정한 각도 코드가 아니다. */
        UNKNOWN_ANGLE_CODE,
        /** 같은 요청에 같은 이미지가 두 번 들어왔다. */
        DUPLICATE_IMAGE
    }
}
