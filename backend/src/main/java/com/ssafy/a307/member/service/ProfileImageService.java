package com.ssafy.a307.member.service;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.member.config.ProfileImageProperties;
import com.ssafy.a307.member.dto.ProfileImageUploadUrlResponse;
import com.ssafy.a307.member.entity.Member;
import com.ssafy.a307.member.image.ProfileImageFormat;
import com.ssafy.a307.member.image.ProfileImageKeys;
import com.ssafy.a307.member.image.ProfileImageNotUploadedException;
import com.ssafy.a307.member.image.ProfileImageStoragePort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 프로필 이미지 등록·변경·삭제.
 * <p>
 * 흐름은 사고 이미지와 같다 — 브라우저가 presigned URL 로 staging 버킷에 직접 올리고,
 * 서버는 완료 통보를 받아 검증한 뒤 service 버킷으로 옮긴다. 서버가 파일 본문을 받지 않으므로
 * multipart 크기 제한(10MB)에 묶이지 않는다.
 *
 * <p>저장소 어댑터가 없으면 503 을 낸다 — {@code AccidentImageService} 와 같은 방식이다.
 */
@Service
@RequiredArgsConstructor
public class ProfileImageService {

    private static final Logger log = LoggerFactory.getLogger(ProfileImageService.class);

    private final MemberService memberService;
    private final ProfileImageProperties properties;
    private final Optional<ProfileImageStoragePort> storagePort;

    /**
     * 업로드 URL 을 내준다. 형식과 크기를 먼저 거르는 이유는, 여기서 막으면 사용자가
     * 20MB 를 다 올리고 나서 거절당하는 일이 없기 때문이다.
     */
    @Transactional(readOnly = true)
    public ProfileImageUploadUrlResponse issueUploadUrl(Long memberId, String contentType, long size) {
        Member member = memberService.activeMember(memberId);

        ProfileImageFormat format = ProfileImageFormat.ofContentType(contentType)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "지원하지 않는 형식입니다. " + ProfileImageFormat.supportedLabel() + "만 올릴 수 있습니다."));
        requireWithinSizeLimit(size);

        String stagingKey = ProfileImageKeys.newStagingKey(member.getMemberId());
        ProfileImageStoragePort.PresignedUpload upload = storage().createStagingUploadUrl(
                stagingKey, format.contentType(), properties.presignedUrlValidity());

        return new ProfileImageUploadUrlResponse(
                stagingKey,
                upload.url().toString(),
                upload.expiresAt(),
                upload.requiredHeaders());
    }

    /**
     * 업로드 완료 통보. staging 의 실제 바이트를 검증하고 service 버킷으로 옮긴다.
     * <p>
     * <b>클라이언트가 보낸 키를 그대로 믿지 않는다.</b> 남의 staging 키를 보내 자기 프로필로
     * 만드는 걸 막으려면 소유 검사가 먼저다. 형식도 선언한 {@code Content-Type} 이 아니라
     * 실제 시그니처로 판정한다 — presigned PUT 은 서버를 거치지 않아 본 적 없는 바이트다.
     */
    @Transactional
    public Member complete(Long memberId, String stagingKey) {
        Member member = memberService.activeMember(memberId);
        long id = member.getMemberId();

        if (!ProfileImageKeys.isOwnStagingKey(stagingKey, id)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "잘못된 업로드 키입니다.");
        }

        ProfileImageStoragePort storage = storage();
        ProfileImageStoragePort.StagedObject staged = readStaged(storage, stagingKey);
        requireWithinSizeLimit(staged.size());

        ProfileImageFormat format = ProfileImageFormat.detect(staged.content())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "이미지 파일이 아닙니다. " + ProfileImageFormat.supportedLabel() + "만 올릴 수 있습니다."));

        String serviceKey = ProfileImageKeys.serviceKey(id);
        storage.putService(serviceKey, staged.content(), format.contentType());
        member.changeProfileImage(serviceKey);

        deleteStagingQuietly(storage, stagingKey);
        return member;
    }

    /**
     * 이미지를 지운다. 화면은 기본 이미지를 쓴다.
     * <p>
     * 이미지가 없어도 성공으로 둔다 — 삭제는 멱등이어야 프론트가 버튼을 두 번 눌러도 안전하다.
     */
    @Transactional
    public Member delete(Long memberId) {
        Member member = memberService.activeMember(memberId);
        if (!member.hasProfileImage()) {
            return member;
        }

        storage().deleteService(ProfileImageKeys.serviceKey(member.getMemberId()));
        member.changeProfileImage(null);
        return member;
    }

    /**
     * 탈퇴 뒤 남은 S3 객체를 지운다. <b>트랜잭션 밖에서, 실패해도 넘어간다</b> —
     * 명세가 정한 best-effort 다.
     * <p>
     * 탈퇴는 이미 커밋된 상태라 여기서 예외를 던지면 끝난 탈퇴가 실패로 보인다.
     * 저장소가 구성되지 않은 환경에서도 탈퇴 자체는 되어야 하므로 503 도 내지 않는다.
     * 지우지 못한 객체는 로그로 남겨 나중에 손으로 정리할 수 있게 한다.
     */
    public void deleteObjectQuietly(String serviceKey) {
        if (serviceKey == null || serviceKey.isBlank() || storagePort.isEmpty()) {
            return;
        }
        try {
            storagePort.get().deleteService(serviceKey);
        } catch (RuntimeException e) {
            log.warn("탈퇴 회원의 프로필 이미지 삭제 실패 — 수동 정리가 필요하다. key={}", serviceKey, e);
        }
    }

    /**
     * 조회용 presigned GET URL. 이미지가 없거나 저장소가 없으면 {@code null} 이다 —
     * 프로필 조회가 저장소 유무로 실패하면 안 되기 때문에 여기서는 503 을 내지 않는다.
     */
    @Transactional(readOnly = true)
    public String downloadUrlOrNull(Member member) {
        if (!member.hasProfileImage() || storagePort.isEmpty()) {
            return null;
        }
        return storagePort.get()
                .createDownloadUrl(member.getProfileImageKey(), properties.presignedUrlValidity())
                .toString();
    }

    private ProfileImageStoragePort.StagedObject readStaged(ProfileImageStoragePort storage,
                                                           String stagingKey) {
        try {
            return storage.readStaging(stagingKey);
        } catch (ProfileImageNotUploadedException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "업로드가 완료되지 않았습니다. 파일을 올린 뒤 다시 시도해 주세요.");
        }
    }

    /**
     * staging 정리는 실패해도 넘어간다. 프로필은 이미 바뀐 뒤라 여기서 예외를 던지면
     * 성공한 작업이 실패로 보인다. 남은 객체는 staging 보관 정책(7일)이 지운다.
     */
    private void deleteStagingQuietly(ProfileImageStoragePort storage, String stagingKey) {
        try {
            storage.deleteStaging(stagingKey);
        } catch (RuntimeException e) {
            log.warn("staging 객체 삭제 실패 — 보관 정책에 맡긴다. key={}", stagingKey, e);
        }
    }

    private void requireWithinSizeLimit(long size) {
        if (size > properties.maxFileSizeBytes()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "프로필 이미지는 " + (properties.maxFileSizeBytes() / (1024 * 1024)) + "MB 이하여야 합니다.");
        }
    }

    private ProfileImageStoragePort storage() {
        return storagePort.orElseThrow(() -> new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE, "이미지 저장소가 구성되지 않았습니다."));
    }
}
