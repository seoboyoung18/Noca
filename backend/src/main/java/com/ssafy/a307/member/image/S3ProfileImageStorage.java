package com.ssafy.a307.member.image;

import com.ssafy.a307.member.config.ProfileImageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * {@link ProfileImageStoragePort} 의 S3 구현.
 * <p>
 * 버킷·리전이 설정돼 있을 때만 빈이 만들어진다. 없으면 소비자가 {@code Optional} 이 비어
 * 있는 걸 보고 503 을 낸다 — 테스트와 설정 없는 로컬에서 AWS 를 요구하지 않기 위해서다.
 *
 * <p>자격증명을 코드에서 만들지 않는다. {@code S3Client.create()} 계열이 쓰는 기본 체인이
 * 로컬에서는 {@code aws configure} 프로필을, 배포 서버에서는 EC2 IAM 역할을 집어 간다.
 *
 * <p><b>{@code @ConditionalOnProperty} 를 쓰지 않는 이유</b> — 그건 값이 "있는지"만 보고
 * 빈 문자열도 있는 것으로 세기 때문에, 버킷을 비워 둔 로컬·테스트에서 빈이 만들어져 버린다.
 */
@Component
@ConditionalOnExpression("'${app.profile-image.staging-bucket:}' != '' "
        + "and '${app.profile-image.service-bucket:}' != ''")
public class S3ProfileImageStorage implements ProfileImageStoragePort {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String stagingBucket;
    private final String serviceBucket;

    public S3ProfileImageStorage(ProfileImageProperties properties) {
        Region region = Region.of(properties.region());
        this.s3 = S3Client.builder().region(region).build();
        this.presigner = S3Presigner.builder().region(region).build();
        this.stagingBucket = properties.stagingBucket();
        this.serviceBucket = properties.serviceBucket();
    }

    @Override
    public PresignedUpload createStagingUploadUrl(String stagingKey, String contentType,
                                                  Duration validity) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(stagingBucket)
                .key(stagingKey)
                .contentType(contentType)
                .build();

        var presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(validity)
                .putObjectRequest(put)
                .build());

        // Content-Type 이 서명에 포함되므로 브라우저가 같은 값을 보내야 한다.
        // 다른 값을 보내면 S3 가 403 으로 거절한다.
        return new PresignedUpload(
                toUri(presigned.url()),
                presigned.expiration(),
                Map.of("Content-Type", contentType));
    }

    @Override
    public StagedObject readStaging(String stagingKey) {
        try {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(stagingBucket)
                    .key(stagingKey)
                    .build());

            return new StagedObject(object.asByteArray(), object.response().contentType());
        } catch (NoSuchKeyException e) {
            throw new ProfileImageNotUploadedException(stagingKey, e);
        }
    }

    @Override
    public void putService(String serviceKey, byte[] content, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(serviceBucket)
                        .key(serviceKey)
                        // 키에 확장자가 없어 형식을 알 수 있는 곳이 여기뿐이다
                        .contentType(contentType)
                        .build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public void deleteStaging(String stagingKey) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(stagingBucket)
                .key(stagingKey)
                .build());
    }

    @Override
    public void deleteService(String serviceKey) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(serviceBucket)
                .key(serviceKey)
                .build());
    }

    @Override
    public URI createDownloadUrl(String serviceKey, Duration validity) {
        var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(validity)
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(serviceBucket)
                        .key(serviceKey)
                        .build())
                .build());

        return toUri(presigned.url());
    }

    private static URI toUri(java.net.URL url) {
        try {
            return url.toURI();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("presigned URL 을 URI 로 바꾸지 못했습니다: " + url, e);
        }
    }
}
