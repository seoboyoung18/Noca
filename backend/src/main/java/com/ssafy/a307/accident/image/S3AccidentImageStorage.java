package com.ssafy.a307.accident.image;

import com.ssafy.a307.accident.entity.ImageVariant;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.storage.ObjectStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * {@link AccidentImageStoragePort} 의 S3 구현.
 *
 * <p>{@code member/image/S3ProfileImageStorage} 와 {@code estimatevalidation/file/S3DocumentStorage}
 * 의 구조를 그대로 따랐다 — 한 저장소에 여러 가지 S3 접근 방식을 만들지 않는다.
 * <ul>
 *   <li><b>{@code @ConditionalOnProperty} 를 쓰지 않는다.</b> 그건 값이 "있는지" 만 보고 빈
 *       문자열도 있는 것으로 세기 때문에, 버킷을 비워 둔 로컬·테스트에서 빈이 만들어져 버린다</li>
 *   <li><b>자격증명을 코드에서 만들지 않는다.</b> 기본 체인이 로컬에서는 {@code aws configure}
 *       프로필을, 배포 서버에서는 EC2 IAM 역할을 집어 간다.
 *       {@code credentialsProvider(...)} 를 지정하지 않는다</li>
 *   <li><b>리전을 반드시 명시한다.</b> 비워 두면 SDK 가 {@code GetBucketLocation} 을 부르는데
 *       팀 IAM 정책에 그 액션이 없어 AccessDenied 로 죽는다. 같은 이유로
 *       {@code headBucket}·{@code listBuckets} 도 부르지 않는다.
 *       {@link #head}는 <b>오브젝트</b> 메타를 읽는 {@code HeadObject} 이고 {@code HeadBucket} 이 아니다</li>
 * </ul>
 *
 * <h2>어느 버킷에 무엇이 가는가</h2>
 * <table border="1">
 *   <caption>버킷 사용</caption>
 *   <tr><th>버킷</th><th>담는 것</th><th>수명</th></tr>
 *   <tr><td>staging</td><td>{@code ORIGINAL} — 브라우저가 presigned PUT 으로 직접 올린다</td><td><b>7일 뒤 자동 삭제</b></td></tr>
 *   <tr><td>service</td><td>{@code RESIZED}·{@code THUMBNAIL}·(뒤에 {@code BLURRED})</td><td>영구</td></tr>
 * </table>
 *
 * <p><b>견적서 문서({@code S3DocumentStorage})와 다르다.</b> 그쪽은 multipart 로 서버를 거쳐 오므로
 * "브라우저가 직접 올린 바이트를 서버가 보기 전에 격리" 라는 staging 의 존재 이유가 성립하지 않아
 * service 하나만 쓴다. <b>사고 이미지는 브라우저가 S3 에 직접 PUT 하므로 그 격리가 필요하다.</b>
 *
 * <p>덤으로 얻는 것이 하나 있다 — <b>{@code ORIGINAL} 에는 EXIF(GPS·기기 정보)가 남는다</b>
 * ({@code AccidentImagePreprocessor} 는 파생본에서만 EXIF 를 지운다). 그 원본이 staging 에 있으면
 * 7일 뒤 저절로 사라지고, 화면에 나가는 파생본만 영구 보관된다 —
 * {@code S15P21A307-388} 이 응답에서 {@code ORIGINAL} 을 감춘 것과 방향이 같다.
 *
 * <p><b>버킷은 키의 variant 로 고른다.</b> 포트는 키 하나만 넘기고 어느 버킷인지 말하지 않으므로,
 * {@link AccidentImageKeys} 가 만든 {@code …/{variant}.{ext}} 에서 variant 를 읽어 정한다.
 * 키는 서버만 만들고 형식이 고정돼 있어 결정적이다. 형식을 벗어난 키는 조용히 엉뚱한 버킷을
 * 건드리지 않도록 거절한다.
 *
 * <h2>업로드 제약 2단 방어 — 실제로 무엇이 강제되는가</h2>
 * 포트 Javadoc 이 {@code content-length-range} 를 적었지만 <b>그것은 presigned POST 의 정책
 * 조건이고, AWS SDK for Java v2 의 {@code S3Presigner} 는 presigned POST 를 지원하지 않는다</b>
 * (aws/aws-sdk-java-v2#1493 · #6577, 2026-09-09 확인). 그래서 <b>범위 대신 정확값</b>을 서명한다 —
 * {@code PutObjectRequest.contentLength(신고값)} 이다. 범위보다 오히려 엄격해서, FE 가 신고한
 * 크기와 1바이트라도 다르면 S3 가 403 {@code SignatureDoesNotMatch} 로 거절한다.
 * 실제로 어떤 헤더가 서명되는지는 {@code signedHeaders()} 로 확인해 테스트로 고정했다.
 *
 * <p>정책 서명을 손으로 계산하지 않았다 — 서명 계산 오류는 조용히 보안 구멍이 된다.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${app.object-storage.staging-bucket:}' != '' "
        + "and '${app.object-storage.service-bucket:}' != ''")
public class S3AccidentImageStorage implements AccidentImageStoragePort {

    /** S3 가 {@code Content-Type} 을 돌려주지 않을 때 쓰는 값. 크기·형식 검증은 3단이 바이트로 다시 한다. */
    private static final String UNKNOWN_CONTENT_TYPE = "application/octet-stream";

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String stagingBucket;
    private final String serviceBucket;

    /**
     * 생성자가 둘이라 {@code @Autowired} 를 명시한다 — 생성자가 하나일 때만 자동으로 골라진다.
     * 아래 생성자는 테스트 전용이다.
     */
    @Autowired
    public S3AccidentImageStorage(ObjectStorageProperties properties) {
        Region region = Region.of(properties.region());
        this.s3 = S3Client.builder().region(region).build();
        this.presigner = S3Presigner.builder().region(region).build();
        this.stagingBucket = properties.stagingBucket();
        this.serviceBucket = properties.serviceBucket();
        log.info("사고 이미지 저장소: S3 (region={})", properties.region());
    }

    /**
     * 테스트용. SDK 클라이언트를 목이나 더미 자격증명으로 주고 <b>요청 객체가 맞게 만들어지는지만</b>
     * 본다 — 실제 AWS 를 부르는 테스트는 자격증명·네트워크가 없는 CI 에서 깨진다.
     */
    S3AccidentImageStorage(S3Client s3, S3Presigner presigner, String stagingBucket, String serviceBucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.stagingBucket = stagingBucket;
        this.serviceBucket = serviceBucket;
    }

    // ------------------------------------------------------------------ 업로드 (-142)

    /**
     * 브라우저가 원본을 직접 올릴 presigned PUT URL. staging 버킷을 향한다.
     *
     * <p><b>{@code requiredHeaders} 를 짐작으로 채우지 않는다.</b> {@code signedHeaders()} 가
     * 돌려주는 실제 서명 헤더에서 만들어, 여기 담긴 것과 S3 가 요구하는 것이 어긋날 수 없게 한다.
     * 어긋나면 FE 가 403 을 받고 원인을 찾기 어렵다.
     */
    @Override
    public PresignedUpload createPresignedUploadUrl(UploadUrlRequest request) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(bucketFor(request.storageKey()))
                .key(request.storageKey())
                .contentType(request.contentType())
                .contentLength(request.contentLength())
                .build();

        PresignedPutObjectRequest presigned = presigner.presignPutObject(PutObjectPresignRequest.builder()
                .signatureDuration(request.validity())
                .putObjectRequest(put)
                .build());

        return new PresignedUpload(toUri(presigned.url()), presigned.expiration(), requiredHeaders(presigned));
    }

    /**
     * 서명에 포함된 헤더 중 <b>클라이언트가 직접 실어야 하는 것</b>만 추린다.
     *
     * <p>{@code Host} 는 브라우저가 URL 에서 자동으로 만들고, {@code x-amz-*} 서명 값들은 쿼리
     * 문자열에 이미 들어 있다. FE 가 손으로 붙여야 하는 것은 {@code Content-*} 뿐이다.
     * 값은 서명된 요청 자체에서 읽어 오므로 하드코딩과 어긋날 수 없다.
     *
     * <p><b>이름은 SDK 가 준 그대로 둔다 — 실측하니 소문자다</b>
     * ({@code content-type}·{@code content-length}). HTTP 헤더 이름은 대소문자를 구분하지
     * 않으므로 클라이언트가 그대로 보내면 된다. 보기 좋으라고 대문자로 바꾸면 "서명된 것을
     * 그대로 돌려준다" 는 보장이 깨진다 — {@code member/image/S3ProfileImageStorage} 는
     * {@code "Content-Type"} 을 하드코딩해 돌려주므로 이름 표기가 다르다(협의 항목).
     */
    private static Map<String, String> requiredHeaders(PresignedPutObjectRequest presigned) {
        Map<String, String> headers = new LinkedHashMap<>();
        presigned.signedHeaders().forEach((name, values) -> {
            if (name.toLowerCase(Locale.ROOT).startsWith("content-") && !values.isEmpty()) {
                headers.put(name, values.getFirst());
            }
        });
        return Map.copyOf(headers);
    }

    /**
     * 화면이 이미지를 띄울 조회용 presigned GET URL. 서비스 버킷의 파생본만 대상이다.
     *
     * <p><b>{@code ORIGINAL} 은 여기서 거절한다.</b> 서비스가 실수로 원본 키를 넘겨도
     * staging 버킷의 EXIF 달린 원본이 브라우저로 나가지 않게 하는 두 번째 방어선이다.
     * 호출자가 이미 파생본만 고르지만, 그 한 줄이 바뀌면 조용히 원본이 새 나간다.
     *
     * <p><b>네트워크 호출이 없다.</b> presign 은 로컬 서명 계산이라 오브젝트가 없어도 성공한다 —
     * 존재 확인을 하려면 asset 마다 {@code HeadObject} 왕복이 생긴다(포트 Javadoc 참조).
     */
    @Override
    public PresignedDownload createPresignedDownloadUrl(String storageKey, Duration validity) {
        if (validity == null || validity.isZero() || validity.isNegative()) {
            throw new IllegalArgumentException("validity must be positive");
        }
        if (isOriginal(storageKey)) {
            throw new IllegalArgumentException("ORIGINAL 은 조회 URL 을 발급하지 않는다");
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

    // ------------------------------------------------------------------ 읽기

    /**
     * 오브젝트 메타만 읽는다({@code HeadObject}). 3단 재검증이 실제 크기를 확인할 때 쓴다.
     *
     * <p><b>없는 키는 404 다</b> — "아직 올라오지 않았다" 는 뜻이고, 다시 시도해도 없다.
     * 503 으로 주면 "잠시 후 다시" 라는 뜻이 되어 FE 가 무한정 재시도한다.
     */
    @Override
    public StoredObject head(String storageKey) {
        String bucket = bucketFor(storageKey);
        try {
            HeadObjectResponse response = s3.headObject(
                    HeadObjectRequest.builder().bucket(bucket).key(storageKey).build());
            return new StoredObject(storageKey, response.contentLength(), contentTypeOf(response.contentType()));
        } catch (NoSuchKeyException e) {
            throw notFound();
        } catch (S3Exception e) {
            // HeadObject 는 본문이 없어 없는 키에도 404 상태만 오고 NoSuchKeyException 이 아닐 수 있다.
            if (e.statusCode() == 404) throw notFound();
            throw unavailable("이미지 메타를 읽지 못했다", storageKey, e);
        } catch (RuntimeException e) {
            throw unavailable("이미지 메타를 읽지 못했다", storageKey, e);
        }
    }

    /** 전처리를 위해 원본 바이트를 읽는다. 없는 키는 {@link #head} 와 같은 이유로 404 다. */
    @Override
    public byte[] read(String storageKey) {
        String bucket = bucketFor(storageKey);
        try {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(storageKey).build());
            return object.asByteArray();
        } catch (NoSuchKeyException e) {
            throw notFound();
        } catch (RuntimeException e) {
            throw unavailable("이미지를 읽지 못했다", storageKey, e);
        }
    }

    // ------------------------------------------------------------------ 쓰기·삭제

    /**
     * 전처리본 저장. 서비스 버킷으로 간다.
     *
     * <p><b>{@code contentType} 을 반드시 넣는다.</b> 키에 확장자가 있어도 S3 오브젝트 메타로
     * 필요하다 — 나중에 presigned 로 내려줄 때 브라우저가 이 값으로 렌더링을 정한다.
     */
    @Override
    public StoredObject store(StoreImage request) {
        byte[] content = request.content();
        String bucket = bucketFor(request.storageKey());
        try {
            s3.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(request.storageKey())
                            .contentType(request.contentType())
                            .build(),
                    RequestBody.fromBytes(content));
        } catch (RuntimeException e) {
            throw unavailable("이미지를 저장하지 못했다", request.storageKey(), e);
        }
        return new StoredObject(request.storageKey(), content.length, request.contentType());
    }

    /**
     * <b>멱등하다.</b> 없는 키를 지우라는 요청은 성공으로 취급한다 — S3 의 {@code DeleteObject} 가
     * 원래 그렇게 동작한다. 삭제 흐름이 완료 통보 전 업로드분까지 지우려고 결정적 키를 함께
     * 넘기므로 <b>존재하지 않는 키가 정상적으로 들어온다</b>
     * ({@code AccidentImageService.speculativeOriginalKey}).
     *
     * <p>그래서 {@code NoSuchKey} 를 예외로 바꾸지 않는다. 바꾸면 삭제 흐름이 통째로 깨진다.
     */
    @Override
    public void delete(String storageKey) {
        String bucket = bucketFor(storageKey);
        try {
            s3.deleteObject(
                    DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
        } catch (NoSuchKeyException alreadyGone) {
            // 멱등. S3 는 보통 200 을 주지만 구현에 따라 예외가 올 수 있어 여기서도 삼킨다.
        } catch (RuntimeException e) {
            throw unavailable("이미지를 삭제하지 못했다", storageKey, e);
        }
    }

    // ------------------------------------------------------------------ 버킷 선택

    /**
     * 키의 variant 로 버킷을 정한다. {@code ORIGINAL} 만 staging 이고 나머지는 service 다.
     *
     * <p>형식을 벗어난 키는 거절한다 — 조용히 엉뚱한 버킷을 건드리는 것보다 낫다.
     * 키는 {@link AccidentImageKeys} 가 서버에서만 만들므로 정상 흐름에서는 걸리지 않는다.
     *
     * <p><b>호출자는 이 메서드를 {@code try} 밖에서 부른다.</b> 안에서 부르면 잘못된 키의
     * {@code IllegalArgumentException} 이 저장소 오류를 잡는 {@code catch} 에 걸려 503 으로 둔갑한다.
     */
    private String bucketFor(String storageKey) {
        return isOriginal(storageKey) ? stagingBucket : serviceBucket;
    }

    private static boolean isOriginal(String storageKey) {
        int slash = storageKey.lastIndexOf('/');
        int dot = storageKey.lastIndexOf('.');
        if (slash < 0 || dot <= slash + 1) {
            throw new IllegalArgumentException("storage key must end with /{variant}.{ext}");
        }
        String variant = storageKey.substring(slash + 1, dot).toLowerCase(Locale.ROOT);
        return ImageVariant.ORIGINAL.objectName().equals(variant);
    }

    // ------------------------------------------------------------------ 보조

    private static String contentTypeOf(String fromS3) {
        return fromS3 == null || fromS3.isBlank() ? UNKNOWN_CONTENT_TYPE : fromS3;
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND, "이미지를 찾을 수 없습니다.");
    }

    /** 키는 로그에만 남긴다. 예외 메시지는 사용자에게 갈 수 있다. */
    private BusinessException unavailable(String what, String storageKey, RuntimeException cause) {
        log.error("{}: key={}", what, storageKey, cause);
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "이미지 저장소를 사용할 수 없습니다.");
    }

    private static URI toUri(java.net.URL url) {
        try {
            return url.toURI();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("presigned URL 을 URI 로 바꾸지 못했다", e);
        }
    }
}
