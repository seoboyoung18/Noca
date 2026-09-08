package com.ssafy.a307.accident.image;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 사고 이미지 스토리지 경계. <b>어댑터는 이 작업에서 만들지 않는다</b> —
 * AWS SDK 의존성 추가가 금지되어 있고 {@code S15P21A307-217 S3 버킷·IAM 정책 구성} 이 선행이다.
 * {@code DocumentStoragePort} 가 구현체 없이 포트만 있는 전례를 그대로 따르고, 소비자는
 * {@code Optional<AccidentImageStoragePort>} 로 주입받아 없으면 503 을 준다.
 *
 * <p><b>{@code DocumentStoragePort} 를 확장하지 않고 신설했다</b>(answer25 D5). 견적서 문서는
 * {@code store(byte[])} 와 다운로드 presigned 만 필요한데 이미지는 업로드 presigned·키 기준 읽기·
 * 변형본 저장·삭제가 필요하다. 한 인터페이스에 밀어넣으면 두 도메인이 서로의 메서드를
 * 구현하지 않은 채 공유하게 된다. 다만 키 규칙·예외 처리·presigned 유효시간 프로퍼티 관례는
 * 기존 것을 그대로 따른다.
 *
 * <p><b>제약 3단 방어의 2단이 이 인터페이스에 있다.</b> {@link UploadUrlRequest} 가
 * {@code contentType} 과 {@code maxBytes} 를 함께 받는 이유는, 어댑터가 presigned 자체에
 * {@code content-length-range} 와 {@code Content-Type} 조건을 박아 <b>S3 가 강제</b>하게
 * 만들어야 하기 때문이다. 서버는 원본 바이트를 받지 않으므로 이 조건이 없으면 신고값만 믿게 된다.
 */
public interface AccidentImageStoragePort {

    /** 업로드용 presigned URL. 어댑터는 반드시 크기·Content-Type 조건을 URL 에 반영해야 한다. */
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
     * @param maxBytes presigned 에 박을 크기 상한. 어댑터가 이 값을 무시하면 2단 방어가 사라진다
     */
    record UploadUrlRequest(String storageKey, String contentType, long maxBytes, Duration validity) {
        public UploadUrlRequest {
            requireKey(storageKey);
            if (contentType == null || contentType.isBlank()) {
                throw new IllegalArgumentException("contentType is required");
            }
            if (maxBytes < 1) throw new IllegalArgumentException("maxBytes must be positive");
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
