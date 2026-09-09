package com.ssafy.a307.estimatevalidation.file;

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
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * {@link DocumentStoragePort} 의 S3 구현.
 *
 * <p>{@code member/image/S3ProfileImageStorage} 의 구조를 그대로 따랐다 — 같은 저장소에
 * 두 가지 S3 접근 방식을 만들지 않는다.
 * <ul>
 *   <li><b>{@code @ConditionalOnProperty} 를 쓰지 않는다.</b> 그건 값이 "있는지" 만 보고 빈
 *       문자열도 있는 것으로 세기 때문에, 버킷을 비워 둔 로컬·테스트에서 빈이 만들어져 버린다</li>
 *   <li><b>자격증명을 코드에서 만들지 않는다.</b> 기본 체인이 로컬에서는 {@code aws configure}
 *       프로필을, 배포 서버에서는 EC2 IAM 역할을 집어 간다.
 *       {@code credentialsProvider(...)} 를 지정하지 않는다</li>
 *   <li><b>리전을 반드시 명시한다.</b> 비워 두면 SDK 가 {@code GetBucketLocation} 을 부르는데
 *       팀 IAM 정책에 그 액션이 없어 AccessDenied 로 죽는다. 같은 이유로
 *       {@code headBucket}·{@code listBuckets} 도 부르지 않는다</li>
 * </ul>
 *
 * <h2>어느 버킷에 무엇이 가는가 — 서비스 버킷 하나만 쓴다</h2>
 * <table border="1">
 *   <caption>버킷 사용</caption>
 *   <tr><th>버킷</th><th>이 어댑터의 사용</th></tr>
 *   <tr><td>staging</td><td><b>쓰지 않는다</b></td></tr>
 *   <tr><td>service</td><td>견적서 원본({@code estimates/…}) · 검증 PDF({@code estimates/reports/…})</td></tr>
 * </table>
 *
 * <p><b>staging 을 쓰지 않는 이유는 두 가지다.</b>
 * <ol>
 *   <li>staging 은 <b>7일 뒤 자동 삭제</b>된다. {@code estimate_validation.s3_key_file} 은 검증
 *       이력이 살아 있는 동안 유효해야 하는데, 원본이 사라지면 그 키가 가리키는 곳이 비어
 *       {@link #read} 가 404 를 준다 — 오래된 검증의 재판독이 영영 불가능해진다</li>
 *   <li>staging 의 존재 이유는 <b>브라우저가 직접 올리는 바이트를 서버가 보기 전에 격리</b>하는
 *       것이다. 견적서는 multipart 로 서버를 거쳐 오고 {@code EstimateFileValidator} 가 이미
 *       바이트를 검사한 뒤이므로 그 격리가 성립하지 않는다</li>
 * </ol>
 *
 * <p><b>견적서 전용 제3의 버킷을 만들지 않았다.</b> 팀 인프라에 버킷은 둘뿐이고 버킷 생성과
 * IAM 정책은 이 작업의 범위 밖이다. 견적서에 차주 이름·차량번호가 실릴 수 있어 접근을 나누는
 * 것은 옳지만, 그것은 <b>버킷이 아니라 {@code estimates/} 접두어에 건 IAM 정책</b>으로 하는 것이
 * 지금 할 수 있는 일이다 (협의 항목).
 *
 * <p><b>키는 이 클래스가 만든다</b>({@link EstimateDocumentKeys}). 호출자가 키를 넘기는
 * 저장 통로가 없으므로 경로 조작이 애초에 성립하지 않는다.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${app.document-storage.provider:}' == 's3' "
        + "and '${app.object-storage.service-bucket:}' != ''")
public class S3DocumentStorage implements DocumentStoragePort {

    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    /**
     * 생성자가 둘이라 {@code @Autowired} 를 명시한다 — 생성자가 하나일 때만 자동으로 골라진다.
     * 아래 생성자는 테스트 전용이다.
     */
    @Autowired
    public S3DocumentStorage(ObjectStorageProperties properties) {
        Region region = Region.of(properties.region());
        this.s3 = S3Client.builder().region(region).build();
        this.presigner = S3Presigner.builder().region(region).build();
        this.bucket = properties.serviceBucket();
        log.info("견적서 문서 저장소: S3 (region={})", properties.region());
    }

    /**
     * 테스트용. SDK 클라이언트를 목으로 주고 <b>요청 객체가 맞게 만들어지는지만</b> 본다 —
     * 실제 AWS 를 부르는 테스트는 자격증명·네트워크가 없는 CI 에서 깨진다.
     */
    S3DocumentStorage(S3Client s3, S3Presigner presigner, String bucket) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
    }

    @Override
    public StoredDocument store(StoreDocument request) {
        byte[] content = request.content();
        String key = EstimateDocumentKeys.documentKey(request.extension(), Instant.now());
        put(key, content, request.contentType());
        return new StoredDocument(key, content.length, request.contentType());
    }

    /**
     * 저장한 문서를 그대로 읽는다. 판독 어댑터가 파일에 닿는 유일한 통로다.
     *
     * <p><b>없는 키는 404 다</b> — {@code LocalDocumentStorageAdapter} 와 같은 매핑이다.
     * 503 으로 주면 "잠시 후 다시" 라는 뜻이 되는데, 지워진 오브젝트는 다시 시도해도 없다.
     */
    @Override
    public byte[] read(String storageKey) {
        try {
            ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(storageKey).build());
            return object.asByteArray();
        } catch (NoSuchKeyException e) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "견적서 파일을 찾을 수 없습니다.");
        } catch (RuntimeException e) {
            // 키는 로그에만 남긴다. 예외 메시지는 사용자에게 갈 수 있다.
            log.error("문서를 읽지 못했다: key={}", storageKey, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "견적서 파일을 읽지 못했습니다.");
        }
    }

    @Override
    public StoredDocument storeReport(long validationId, byte[] pdfContent) {
        if (pdfContent == null || pdfContent.length == 0) {
            throw new IllegalArgumentException("pdfContent must not be empty");
        }
        String key = EstimateDocumentKeys.reportKey(validationId, Instant.now());
        put(key, pdfContent, PDF_CONTENT_TYPE);
        return new StoredDocument(key, pdfContent.length, PDF_CONTENT_TYPE);
    }

    /**
     * <b>멱등하다.</b> 없는 키를 지우라는 요청은 성공으로 취급한다 — S3 의
     * {@code DeleteObject} 가 원래 그렇게 동작한다. 삭제 흐름이 아직 만들어지지 않은
     * PDF 키를 함께 넘기므로 이 규약이 필요하다
     * ({@code EstimateFileValidationService.delete}).
     */
    @Override
    public void delete(String storageKey) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(storageKey).build());
    }

    @Override
    public URI createPresignedDownloadUrl(String storageKey, Duration validity, String downloadFilename) {
        GetObjectRequest.Builder get = GetObjectRequest.builder().bucket(bucket).key(storageKey);
        if (downloadFilename != null && !downloadFilename.isBlank()) {
            // RFC 5987 — 한글 파일명을 헤더에 안전하게 싣는다. 원시 문자열을 그대로 넣으면
            // 비ASCII 가 깨지거나 헤더가 잘린다. 값은 서버가 만든 것뿐이다(사용자 입력 없음).
            get.responseContentDisposition(
                    "attachment; filename*=UTF-8''"
                            + URLEncoder.encode(downloadFilename, StandardCharsets.UTF_8)
                            .replace("+", "%20"));
        }
        var presigned = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(validity)
                .getObjectRequest(get.build())
                .build());
        return toUri(presigned.url());
    }

    private void put(String key, byte[] content, String contentType) {
        try {
            s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(),
                    RequestBody.fromBytes(content));
        } catch (RuntimeException e) {
            log.error("문서 저장에 실패했다: key={}", key, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "문서를 저장하지 못했습니다.");
        }
    }

    private static URI toUri(java.net.URL url) {
        try {
            return url.toURI();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("presigned URL 을 URI 로 바꾸지 못했다", e);
        }
    }
}
