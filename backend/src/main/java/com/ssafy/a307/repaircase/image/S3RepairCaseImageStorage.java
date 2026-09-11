package com.ssafy.a307.repaircase.image;

import com.ssafy.a307.common.storage.ObjectStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.net.URL;
import java.time.Duration;

/**
 * 사례 이미지 조회 URL 을 S3 로 발급한다.
 *
 * <p><b>service 버킷만 본다.</b> 사례 이미지는 사용자 업로드가 아니라 파이프라인이 미리 넣어 둔
 * 것이라 격리 단계가 없다. staging 은 7일 뒤 지워지므로 여기 두면 안 된다.
 *
 * <p><b>{@code @ConditionalOnExpression} 을 쓰는 이유</b> — {@code @ConditionalOnProperty} 는
 * 값이 "있는지" 만 보고 빈 문자열도 있다고 판정한다. 버킷 기본값이 빈 문자열이라 그러면
 * 설정 없는 로컬에서도 빈이 떠 AWS 자격증명을 요구한다. {@code S3AccidentImageStorage} 와
 * 같은 판단이다.
 *
 * <p><b>S3Client 를 만들지 않는다.</b> 조회 URL 서명에는 presigner 만 있으면 되고,
 * presigner 는 네트워크를 타지 않는다 — 서명은 로컬 계산이다.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${app.object-storage.service-bucket:}' != ''")
public class S3RepairCaseImageStorage implements RepairCaseImageStoragePort {

    private final S3Presigner presigner;
    private final String serviceBucket;

    public S3RepairCaseImageStorage(ObjectStorageProperties properties) {
        this.presigner = S3Presigner.builder().region(Region.of(properties.region())).build();
        this.serviceBucket = properties.serviceBucket();
        log.info("사례 이미지 저장소: S3 (region={})", properties.region());
    }

    /** 테스트용. presigner 를 더미 자격증명으로 주고 요청 객체가 맞게 만들어지는지만 본다. */
    S3RepairCaseImageStorage(S3Presigner presigner, String serviceBucket) {
        this.presigner = presigner;
        this.serviceBucket = serviceBucket;
    }

    @Override
    public PresignedDownload createPresignedDownloadUrl(String storageKey, Duration validity) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey is required");
        }
        if (validity == null || validity.isZero() || validity.isNegative()) {
            throw new IllegalArgumentException("validity must be positive");
        }

        PresignedGetObjectRequest presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(validity)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(serviceBucket)
                        .key(storageKey)
                        .build())
                .build());

        return new PresignedDownload(toUri(presigned.url()), presigned.expiration());
    }

    private static URI toUri(URL url) {
        try {
            return url.toURI();
        } catch (Exception e) {
            throw new IllegalStateException("presigned URL 을 URI 로 바꾸지 못했다", e);
        }
    }
}
