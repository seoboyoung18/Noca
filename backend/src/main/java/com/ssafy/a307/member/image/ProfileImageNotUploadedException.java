package com.ssafy.a307.member.image;

/**
 * 업로드 완료 통보를 받았는데 staging 에 그 객체가 없을 때.
 * <p>
 * 서버 잘못이 아니라 클라이언트가 presigned URL 로 실제 PUT 을 하지 않았거나,
 * staging 보관 기간(7일)이 지난 키를 다시 보낸 경우다. 그래서 500 이 아니라 400 으로 나간다 —
 * 변환은 {@code ProfileImageService} 가 한다.
 */
public class ProfileImageNotUploadedException extends RuntimeException {

    public ProfileImageNotUploadedException(String stagingKey, Throwable cause) {
        super("업로드된 파일을 찾을 수 없습니다: " + stagingKey, cause);
    }
}
