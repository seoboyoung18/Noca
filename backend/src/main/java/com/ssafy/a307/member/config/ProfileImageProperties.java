package com.ssafy.a307.member.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 프로필 이미지 업로드 설정.
 *
 * <p><b>버킷·리전이 여기 있는 건 임시다.</b> 두 버킷은 사고 이미지·견적 문서도 함께 쓸
 * 공용 인프라라, 그쪽 어댑터가 생기면 설정을 공용 위치로 옮겨야 한다. 지금 미리 공용
 * 클래스를 만들지 않은 이유는 그 패키지가 담당 밖이고, 쓰는 곳이 아직 하나뿐이어서다.
 *
 * <p>액세스 키는 여기 두지 않는다 — 자격증명은 AWS 기본 체인
 * (로컬 {@code aws configure}, 배포 서버 EC2 IAM 역할)에서 온다.
 *
 * @param stagingBucket       업로드 격리용. 브라우저가 presigned PUT 으로 직접 올린다
 * @param serviceBucket       검증 통과본. 서버만 접근한다
 * @param maxFileSizeBytes    한 장 상한. 사고 이미지(20MB)보다 작게 잡는다 —
 *                            프로필은 작은 크기로 소비된다
 * @param presignedUrlMinutes 업로드·조회 URL 유효 시간(분)
 */
@Validated
@ConfigurationProperties(prefix = "app.profile-image")
public record ProfileImageProperties(

        String region,

        String stagingBucket,

        String serviceBucket,

        @Min(1) @Max(20_971_520) int maxFileSizeBytes,

        @Min(1) @Max(1440) int presignedUrlMinutes) {

    public Duration presignedUrlValidity() {
        return Duration.ofMinutes(presignedUrlMinutes);
    }

    /** 어댑터를 만들 수 있는 설정인지. 버킷이 비어 있으면 저장소 없이 뜬다. */
    public boolean isConfigured() {
        return hasText(region) && hasText(stagingBucket) && hasText(serviceBucket);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
