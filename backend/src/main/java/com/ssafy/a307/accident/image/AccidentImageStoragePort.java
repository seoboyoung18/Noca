package com.ssafy.a307.accident.image;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 사고 이미지 스토리지 경계.
 *
 * <p><b>구현체는 프로퍼티로만 붙는다.</b> 소비자는 {@code Optional<AccidentImageStoragePort>} 로
 * 받아 없으면 503 을 준다 — 가짜 성공이나 하드코딩 URL 을 만들지 않는다. 버킷을 비워 둔
 * 로컬·테스트에서 AWS 를 요구하지 않기 위해서다.
 *
 * <p><b>이 Javadoc 은 원래 "어댑터는 이 작업에서 만들지 않는다 — AWS SDK 의존성 추가가
 * 금지되어 있고 S15P21A307-217 이 선행이다" 고 적었다. 둘 다 더 이상 사실이 아니다 — 정정.</b>
 * AWS SDK 는 {@code S15P21A307-89}(프로필 이미지) 때 이미 들어왔고
 * ({@code software.amazon.awssdk:s3}, presigner 는 같은 모듈에 포함된다),
 * 버킷과 기본 IAM 권한({@code ListBucket}·{@code PutObject}·{@code GetObject}·
 * {@code DeleteObject}·{@code HeadObject})은 실측으로 확인됐다.
 * 그래서 {@code S15P21A307-139}·{@code S15P21A307-142} 가 {@link S3AccidentImageStorage} 를 만들었다.
 *
 * <p><b>{@code DocumentStoragePort} 를 확장하지 않고 신설했다</b>(answer25 D5). 견적서 문서는
 * {@code store(byte[])} 와 다운로드 presigned 만 필요한데 이미지는 업로드 presigned·키 기준 읽기·
 * 변형본 저장·삭제가 필요하다. 한 인터페이스에 밀어넣으면 두 도메인이 서로의 메서드를
 * 구현하지 않은 채 공유하게 된다. 다만 키 규칙·예외 처리·presigned 유효시간 프로퍼티 관례는
 * 기존 것을 그대로 따른다.
 *
 * <p><b>제약 3단 방어의 2단이 이 인터페이스에 있다.</b> {@link UploadUrlRequest} 가
 * {@code contentType} 과 {@code contentLength} 를 함께 받는 이유는, 어댑터가 presigned 자체에
 * 그 조건을 박아 <b>S3 가 강제</b>하게 만들어야 하기 때문이다. 서버는 원본 바이트를 받지 않으므로
 * 이 조건이 없으면 신고값만 믿게 된다.
 *
 * <p><b>{@code content-length-range} 가 아니라 정확값이다 — 정정.</b> 이 Javadoc 은 원래
 * {@code content-length-range} 를 적었지만 그것은 presigned <b>POST</b> 의 정책 문서 조건이고,
 * <b>AWS SDK for Java v2 의 {@code S3Presigner} 는 presigned POST 를 지원하지 않는다</b>
 * (aws/aws-sdk-java-v2#1493 · #6577, 2026-09-09 확인). 그래서 {@code S3AccidentImageStorage} 는
 * <b>범위 대신 신고 크기 정확값</b>을 서명한다. 범위보다 오히려 엄격하다 — 신고한 크기와
 * 1바이트라도 다르면 S3 가 403 으로 거절한다. 대신 <b>FE 는 신고한 크기와 정확히 같은 바이트를
 * 올려야 한다.</b>
 */
public interface AccidentImageStoragePort {

    /**
     * 업로드용 presigned URL. 어댑터는 반드시 크기·{@code Content-Type} 조건을 서명에 반영해야 한다.
     * 반환하는 {@link PresignedUpload#requiredHeaders} 는 <b>짐작이 아니라 실제 서명된 헤더</b>에서
     * 만들어야 한다 — 어긋나면 클라이언트가 403 을 받고 원인을 찾기 어렵다.
     */
    PresignedUpload createPresignedUploadUrl(UploadUrlRequest request);

    /** 오브젝트 메타만 읽는다. 3단 재검증에서 실제 크기를 확인할 때 쓴다. */
    StoredObject head(String storageKey);

    /** 전처리를 위해 원본 바이트를 읽는다. */
    byte[] read(String storageKey);

    /** 리사이즈본·썸네일 저장. */
    StoredObject store(StoreImage request);

    /**
     * <b>멱등해야 한다.</b> 없는 키를 지우라는 요청은 성공으로 취급한다 — 삭제 흐름은 완료 통보 전
     * 업로드분까지 지우려고 결정적 키를 함께 넘기므로, 존재하지 않는 키가 정상적으로 들어온다.
     */
    void delete(String storageKey);

    default void deleteAll(List<String> storageKeys) {
        storageKeys.forEach(this::delete);
    }

    /**
     * @param contentLength presigned 에 박을 <b>정확한</b> 바이트 수. FE 가 신고한 파일 크기이며,
     *                      1단 검증({@code AccidentImageValidator.validateDeclared})이 상한과
     *                      대조한 뒤의 값이다. 어댑터가 이 값을 무시하면 2단 방어가 사라진다.
     *                      <b>상한이 아니라 정확값인 이유</b>는 인터페이스 Javadoc 참조
     */
    record UploadUrlRequest(String storageKey, String contentType, long contentLength, Duration validity) {
        public UploadUrlRequest {
            requireKey(storageKey);
            if (contentType == null || contentType.isBlank()) {
                throw new IllegalArgumentException("contentType is required");
            }
            if (contentLength < 1) throw new IllegalArgumentException("contentLength must be positive");
            Objects.requireNonNull(validity, "validity");
            if (validity.isZero() || validity.isNegative()) {
                throw new IllegalArgumentException("validity must be positive");
            }
        }
    }

    /**
     * @param requiredHeaders 클라이언트가 PUT 할 때 그대로 보내야 하는 헤더.
     *                        빠지면 서명이 맞지 않아 S3 가 403 을 준다
     */
    record PresignedUpload(URI url, Instant expiresAt, Map<String, String> requiredHeaders) {
        public PresignedUpload {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(expiresAt, "expiresAt");
            requiredHeaders = requiredHeaders == null ? Map.of() : Map.copyOf(requiredHeaders);
        }
    }

    record StoredObject(String storageKey, long size, String contentType) {
        public StoredObject {
            requireKey(storageKey);
            if (size < 1) throw new IllegalArgumentException("size must be positive");
            if (contentType == null || contentType.isBlank()) {
                throw new IllegalArgumentException("contentType is required");
            }
        }
    }

    record StoreImage(String storageKey, byte[] content, String contentType) {
        public StoreImage {
            requireKey(storageKey);
            content = content == null ? null : content.clone();
            Objects.requireNonNull(content, "content");
            if (content.length == 0) throw new IllegalArgumentException("content must not be empty");
            if (contentType == null || contentType.isBlank()) {
                throw new IllegalArgumentException("contentType is required");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    private static void requireKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey is required");
        }
        if (storageKey.length() > AccidentImageKeys.MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "storageKey must not exceed " + AccidentImageKeys.MAX_KEY_LENGTH + " characters");
        }
    }
}
