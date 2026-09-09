package com.ssafy.a307.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 팀 공용 오브젝트 저장소(S3) 설정 — <b>버킷과 리전 하나만 두는 자리다.</b>
 *
 * <p><b>왜 공용인가</b> — 팀에는 버킷이 두 개뿐이고, 프로필 이미지·사고 이미지·견적 문서·
 * 검증 PDF 가 그 둘을 나눠 쓴다. 도메인마다 이름공간을 따로 두면 같은 버킷을 가리키는 설정이
 * 셋이 되고, 하나만 바꿨을 때 조용히 어긋난다. {@code ProfileImageProperties} Javadoc 이
 * "그쪽 어댑터가 생기면 설정을 공용 위치로 옮겨야 한다" 고 남긴 메모가 이것이다.
 *
 * <p><b>{@code member}·{@code estimatevalidation} 어느 쪽에도 두지 않았다.</b> 도메인 패키지에
 * 두면 다시 한쪽 담당 소유가 된다.
 *
 * <p><b>액세스 키는 여기 두지 않는다.</b> 자격증명은 AWS 기본 체인
 * (로컬 {@code aws configure}, 배포 서버 EC2 IAM 역할)에서만 온다. 이 record 에 키 필드가
 * 없으므로 기본 {@code toString} 이 키를 흘릴 여지도 없다.
 *
 * <p><b>리전은 반드시 설정한다.</b> 비워 두면 SDK 가 버킷 위치를 물어보려고
 * {@code GetBucketLocation} 을 부르는데, 팀 IAM 정책에 그 액션이 없어 AccessDenied 로 죽는다.
 * 기본값을 {@code application.properties} 에 두고 코드에는 두지 않는다 — 설정이 빠지면
 * 조용히 도는 대신 기동에서 시끄럽게 실패해야 한다.
 *
 * <p><b>버킷 기본값은 빈 문자열이다.</b> 그래야 설정 없는 로컬·테스트에서 어댑터 빈이
 * 만들어지지 않고, 소비자가 받는 {@code Optional} 이 비어 503 이 나간다 — AWS 자격증명을
 * 요구하지 않는다.
 *
 * @param region        예: {@code ap-northeast-2}
 * @param stagingBucket 업로드 격리용. 브라우저가 presigned PUT 으로 직접 올린다.
 *                      <b>7일 뒤 자동 삭제된다</b> — 오래 남아야 하는 것을 여기 두면 안 된다
 * @param serviceBucket 서비스 본문. 검증 통과본·프로필 이미지·견적서 원본·검증 PDF 가 여기 있다
 */
@Validated
@ConfigurationProperties(prefix = "app.object-storage")
public record ObjectStorageProperties(

        String region,

        String stagingBucket,

        String serviceBucket) {

    /** 두 버킷을 모두 쓰는 어댑터(브라우저 직접 업로드)가 뜰 수 있는 설정인지. */
    public boolean isConfigured() {
        return hasText(region) && hasText(stagingBucket) && hasText(serviceBucket);
    }

    /** 서비스 버킷만 쓰는 어댑터(서버 경유 업로드)가 뜰 수 있는 설정인지. */
    public boolean hasServiceBucket() {
        return hasText(region) && hasText(serviceBucket);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
