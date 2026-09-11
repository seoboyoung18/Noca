package com.ssafy.a307.estimate.pdf;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import com.ssafy.a307.common.storage.ObjectStorageProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/**
 * {@link EstimatePdfStoragePort} 의 S3 구현. <b>서비스 버킷</b>에 둔다 — staging 은 7일 뒤
 * 지워지는데 PDF 는 재다운로드 이력으로 남아야 한다.
 *
 * <p>구조는 {@code S3DocumentStorage} 를 따랐다 — 자격증명은 기본 체인, 리전은 반드시 명시,
 * {@code @ConditionalOnProperty} 대신 빈 문자열까지 걸러 내는 {@code @ConditionalOnExpression}.
 */
@Slf4j
@Component
@ConditionalOnExpression("'${app.object-storage.service-bucket:}' != ''")
public class S3EstimatePdfStorage implements EstimatePdfStoragePort {

    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;

    public S3EstimatePdfStorage(ObjectStorageProperties properties) {
        Region region = Region.of(properties.region());
        this.s3 = S3Client.builder().region(region).build();
        this.presigner = S3Presigner.builder().region(region).build();
        this.bucket = properties.serviceBucket();
        log.info("견적 PDF 저장소: S3 (region={})", properties.region());
    }

    @Override
    public StoredPdf store(String reportNo, byte[] pdfContent) {
        if (pdfContent == null || pdfContent.length == 0) {
            throw new IllegalArgumentException("pdfContent must not be empty");
        }
        String key = EstimatePdfKeys.storageKey(reportNo, Instant.now());
        try {
            s3.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).contentType(PDF_CONTENT_TYPE).build(),
                    RequestBody.fromBytes(pdfContent));
        } catch (RuntimeException e) {
            log.error("견적 PDF 저장에 실패했다: key={}", key, e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "PDF를 저장하지 못했습니다.");
        }
        return new StoredPdf(key, pdfContent.length);
    }

    @Override
    public URI createPresignedDownloadUrl(String storageKey, Duration validity, String downloadFilename) {
        GetObjectRequest.Builder get = GetObjectRequest.builder().bucket(bucket).key(storageKey);
        if (downloadFilename != null && !downloadFilename.isBlank()) {
            // RFC 5987 — 한글 파일명을 헤더에 안전하게 싣는다.
            get.responseContentDisposition("attachment; filename*=UTF-8''"
                    + URLEncoder.encode(downloadFilename, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        URL url = presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(validity)
                .getObjectRequest(get.build())
                .build()).url();
        try {
            return url.toURI();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("presigned URL 을 URI 로 바꾸지 못했다", e);
        }
    }
}
