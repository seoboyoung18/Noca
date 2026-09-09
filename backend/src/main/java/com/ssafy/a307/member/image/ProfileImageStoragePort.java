package com.ssafy.a307.member.image;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 프로필 이미지 저장소. 브라우저가 staging 버킷에 직접 PUT 하고, 서버가 검증한 뒤
 * service 버킷으로 옮기는 흐름을 표현한다.
 *
 * <p><b>{@code AccidentImageStoragePort}·{@code DocumentStoragePort} 를 확장하지 않고
 * 신설했다.</b> 그 둘은 아직 구현체가 없어 소비자가 503 을 내는 상태인데, 거기에 어댑터를
 * 붙이면 사고 이미지·견적서 업로드 동작이 담당자 검증 없이 바뀐다. 프로필만 실제 저장소에
 * 연결하기 위해 별도 포트를 둔다.
 *
 * <p>구현체가 없으면 소비자가 {@code Optional} 로 받아 503 을 낸다 — 앞선 두 포트와 같은 방식이다.
 */
public interface ProfileImageStoragePort {

    /**
     * 브라우저가 staging 에 직접 올릴 presigned PUT URL.
     * <p>
     * <b>여기서 용량 상한을 강제하지 못한다.</b> presigned PUT 은 서명에 크기 조건을 담을 수
     * 없다(그건 POST policy 의 기능이다). 그래서 상한은 URL 을 내줄 때 클라이언트가 선언한
     * 크기로 한 번 거르고, 업로드 완료 통보 시점에 실제 객체 크기로 다시 확인한다.
     * staging 버킷이 격리용이고 7일 뒤 자동 삭제되므로 그 사이 위험은 제한적이다.
     */
    PresignedUpload createStagingUploadUrl(String stagingKey, String contentType,
                                           Duration validity);

    /**
     * staging 에 올라온 객체를 통째로 읽는다. 시그니처를 확인해야 해서 본문이 필요하고,
     * 상한이 수 MB 라 메모리에 올려도 된다.
     */
    StagedObject readStaging(String stagingKey);

    /** 검증을 통과한 본문을 service 버킷에 쓴다. 같은 키면 덮어쓴다. */
    void putService(String serviceKey, byte[] content, String contentType);

    void deleteStaging(String stagingKey);

    void deleteService(String serviceKey);

    /** 조회용 presigned GET URL. service 버킷은 비공개라 이 경로로만 볼 수 있다. */
    URI createDownloadUrl(String serviceKey, Duration validity);

    record PresignedUpload(URI url, Instant expiresAt, Map<String, String> requiredHeaders) {
        public PresignedUpload {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(expiresAt, "expiresAt");
            requiredHeaders = requiredHeaders == null ? Map.of() : Map.copyOf(requiredHeaders);
        }
    }

    record StagedObject(byte[] content, String contentType) {
        public StagedObject {
            content = content == null ? null : content.clone();
            Objects.requireNonNull(content, "content");
            if (content.length == 0) {
                throw new IllegalArgumentException("content must not be empty");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }

        public long size() {
            return content.length;
        }
    }
}
