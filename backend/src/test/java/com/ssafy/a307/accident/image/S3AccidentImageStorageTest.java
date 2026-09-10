package com.ssafy.a307.accident.image;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
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
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 사고 이미지 S3 어댑터.
 *
 * <p><b>실제 AWS 를 부르지 않는다.</b> {@code S3Client} 는 목이고, {@code S3Presigner} 는
 * <b>더미 자격증명으로 만든 진짜 presigner</b> 다 — 서명은 순수 계산이라 네트워크를 타지 않는다.
 * 그래야 {@code signedHeaders()} 가 실제로 무엇을 요구하는지 볼 수 있다.
 * 목으로 대신하면 그 값이 내가 지어낸 것이 되어 검증이 성립하지 않는다.
 *
 * <p>버킷 이름·자격증명은 전부 더미다. 실제 값을 테스트에 적지 않는다.
 */
@DisplayName("사고 이미지 S3 저장소 어댑터")
class S3AccidentImageStorageTest {

    private static final String STAGING = "test-staging";
    private static final String SERVICE = "test-service";
    private static final Region REGION = Region.AP_NORTHEAST_2;

    private static final String ORIGINAL_KEY = AccidentImageKeys.key(
            12L, 340L, com.ssafy.a307.accident.entity.ImageVariant.ORIGINAL, "jpg");
    private static final String THUMBNAIL_KEY = AccidentImageKeys.key(
            12L, 340L, com.ssafy.a307.accident.entity.ImageVariant.THUMBNAIL, "jpg");

    private S3Client s3;
    private S3Presigner presigner;
    private S3AccidentImageStorage storage;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        presigner = realPresigner();
        storage = new S3AccidentImageStorage(s3, presigner, STAGING, SERVICE);
    }

    /** 네트워크를 타지 않는 진짜 presigner. 서명은 로컬 계산이다. */
    private static S3Presigner realPresigner() {
        return S3Presigner.builder()
                .region(REGION)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("dummy-access-key", "dummy-secret-key")))
                .build();
    }

    private AccidentImageStoragePort.PresignedUpload issue(long bytes) {
        return storage.createPresignedUploadUrl(new AccidentImageStoragePort.UploadUrlRequest(
                ORIGINAL_KEY, "image/jpeg", bytes, Duration.ofMinutes(10)));
    }

    // ------------------------------------------------------------------ presigned 발급 (-142)

    @Nested
    @DisplayName("업로드 presigned URL")
    class Upload {

        @Test
        @DisplayName("staging 버킷과 리전이 URL 에 반영된다")
        void urlTargetsStagingBucketInRegion() {
            URI url = issue(1024L).url();

            assertThat(url.getHost()).contains(STAGING).contains(REGION.id());
            assertThat(url.getPath()).contains(ORIGINAL_KEY);
            assertThat(url.getQuery()).contains("X-Amz-Signature");
        }

        @Test
        @DisplayName("만료 시각이 요청한 유효시간과 맞는다")
        void expiryMatchesRequestedValidity() {
            Instant before = Instant.now();

            Instant expiresAt = issue(1024L).expiresAt();

            assertThat(expiresAt)
                    .isAfter(before.plus(Duration.ofMinutes(9)))
                    .isBefore(before.plus(Duration.ofMinutes(11)));
        }

        /**
         * <b>이 테스트가 2단 방어의 근거다.</b> {@code requiredHeaders} 를 하드코딩하지 않고
         * {@code signedHeaders()} 에서 만들었으므로, 여기서 실제로 무엇이 서명되는지 고정한다.
         * 어긋나면 FE 가 403 {@code SignatureDoesNotMatch} 를 받고 원인을 찾기 어렵다.
         */
        @Test
        @DisplayName("서명된 헤더에 Content-Type 과 Content-Length 가 들어간다 — 크기가 강제된다")
        void signedHeadersCarryContentTypeAndLength() {
            AccidentImageStoragePort.PresignedUpload upload = issue(2048L);

            // SDK 가 소문자로 준다 — 실측값이다. HTTP 헤더 이름은 대소문자를 구분하지 않는다.
            assertThat(upload.requiredHeaders())
                    .containsEntry("content-type", "image/jpeg")
                    .containsEntry("content-length", "2048");
        }

        /** {@code requiredHeaders} 가 SDK 가 실제로 서명한 것과 어긋나지 않는지 직접 대조한다. */
        @Test
        @DisplayName("requiredHeaders 는 signedHeaders() 에서 그대로 나온다 — 짐작이 아니다")
        void requiredHeadersComeFromSignedHeaders() {
            AccidentImageStoragePort.PresignedUpload upload = issue(2048L);

            PresignedPutObjectRequest signed = presigner.presignPutObject(PutObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofMinutes(10))
                    .putObjectRequest(PutObjectRequest.builder()
                            .bucket(STAGING).key(ORIGINAL_KEY)
                            .contentType("image/jpeg").contentLength(2048L).build())
                    .build());

            upload.requiredHeaders().forEach((name, value) -> {
                assertThat(signed.signedHeaders()).containsKey(name);
                assertThat(signed.signedHeaders().get(name)).containsExactly(value);
            });
            // 서명 헤더 중 Content-* 는 하나도 빠뜨리지 않았다
            long contentHeaders = signed.signedHeaders().keySet().stream()
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith("content-")).count();
            assertThat(upload.requiredHeaders()).hasSize((int) contentHeaders);
        }

        @Test
        @DisplayName("Host 나 x-amz-* 서명값을 requiredHeaders 에 넣지 않는다 — 브라우저·URL 이 처리한다")
        void doesNotAskClientForHostOrSignatureHeaders() {
            assertThat(issue(1024L).requiredHeaders().keySet())
                    .noneSatisfy(name -> assertThat(name.toLowerCase(Locale.ROOT)).isEqualTo("host"))
                    .noneSatisfy(name -> assertThat(name.toLowerCase(Locale.ROOT)).startsWith("x-amz-"));
        }
    }

    // ------------------------------------------------------------------ 버킷 선택

    @Nested
    @DisplayName("조회 presigned URL")
    class Download {

        @Test
        @DisplayName("파생본은 service 버킷을 향하는 GET URL 이 나온다")
        void derivedTargetsServiceBucket() {
            AccidentImageStoragePort.PresignedDownload download =
                    storage.createPresignedDownloadUrl(THUMBNAIL_KEY, Duration.ofMinutes(10));

            assertThat(download.url().toString())
                    .contains(SERVICE)
                    .contains(REGION.id())
                    .contains(THUMBNAIL_KEY)
                    .doesNotContain(STAGING);
        }

        @Test
        @DisplayName("업로드용이 아니라 조회용 서명이다 — GET 으로 서명된다")
        void signsForGet() {
            String url = storage.createPresignedDownloadUrl(THUMBNAIL_KEY, Duration.ofMinutes(10))
                    .url().toString();

            // SigV4 쿼리 서명에는 자격증명 범위와 서명이 들어간다. 서명 자체는 로컬 계산이다.
            assertThat(url).contains("X-Amz-Algorithm").contains("X-Amz-Signature");
        }

        @Test
        @DisplayName("ORIGINAL 키는 거절한다 — 서비스가 실수해도 원본이 새 나가지 않는다")
        void refusesOriginal() {
            assertThatThrownBy(() ->
                    storage.createPresignedDownloadUrl(ORIGINAL_KEY, Duration.ofMinutes(10)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("ORIGINAL");
        }

        @Test
        @DisplayName("만료 시각이 요청한 유효시간과 맞는다")
        void expiryMatchesValidity() {
            Instant before = Instant.now();

            AccidentImageStoragePort.PresignedDownload download =
                    storage.createPresignedDownloadUrl(THUMBNAIL_KEY, Duration.ofMinutes(5));

            assertThat(download.expiresAt())
                    .isBetween(before.plus(Duration.ofMinutes(4)), Instant.now().plus(Duration.ofMinutes(6)));
        }

        @Test
        @DisplayName("유효시간이 0 이하면 거절한다")
        void rejectsNonPositiveValidity() {
            assertThatThrownBy(() -> storage.createPresignedDownloadUrl(THUMBNAIL_KEY, Duration.ZERO))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() ->
                    storage.createPresignedDownloadUrl(THUMBNAIL_KEY, Duration.ofMinutes(-1)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("variant 를 읽을 수 없는 키는 거절한다")
        void malformedKeyIsRejected() {
            assertThatThrownBy(() ->
                    storage.createPresignedDownloadUrl("no-variant-suffix", Duration.ofMinutes(10)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("서명은 네트워크를 타지 않는다 — S3Client 를 부르지 않는다")
        void doesNotCallS3() {
            storage.createPresignedDownloadUrl(THUMBNAIL_KEY, Duration.ofMinutes(10));

            verifyNoInteractions(s3);
        }
    }

    @Nested
    @DisplayName("버킷 선택 — ORIGINAL 은 staging, 파생본은 service")
    class BucketRouting {

        @Test
        @DisplayName("파생본 저장은 service 버킷으로 간다")
        void derivedGoesToService() {
            storage.store(new AccidentImageStoragePort.StoreImage(
                    THUMBNAIL_KEY, "png".getBytes(StandardCharsets.UTF_8), "image/jpeg"));

            ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(s3).putObject(request.capture(), any(RequestBody.class));
            assertThat(request.getValue().bucket()).isEqualTo(SERVICE);
            assertThat(request.getValue().contentType()).isEqualTo("image/jpeg");
        }

        @Test
        @DisplayName("원본 읽기는 staging 버킷에서 한다")
        void originalIsReadFromStaging() {
            when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                    ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), new byte[] {1}));

            storage.read(ORIGINAL_KEY);

            ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
            verify(s3).getObjectAsBytes(request.capture());
            assertThat(request.getValue().bucket()).isEqualTo(STAGING);
        }

        @Test
        @DisplayName("variant 를 읽을 수 없는 키는 거절한다 — 엉뚱한 버킷을 건드리지 않는다")
        void malformedKeyIsRejected() {
            assertThatThrownBy(() -> storage.read("accidents/12/images/340/original"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ------------------------------------------------------------------ head · read

    @Nested
    @DisplayName("메타 조회와 읽기")
    class ReadPath {

        @Test
        @DisplayName("head 는 실제 크기와 Content-Type 을 돌려준다 — 3단 재검증이 쓴다")
        void headReturnsSizeAndType() {
            when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                    HeadObjectResponse.builder().contentLength(4096L).contentType("image/jpeg").build());

            AccidentImageStoragePort.StoredObject stored = storage.head(ORIGINAL_KEY);

            assertThat(stored.size()).isEqualTo(4096L);
            assertThat(stored.contentType()).isEqualTo("image/jpeg");
            ArgumentCaptor<HeadObjectRequest> request = ArgumentCaptor.forClass(HeadObjectRequest.class);
            verify(s3).headObject(request.capture());
            assertThat(request.getValue().bucket()).isEqualTo(STAGING);
        }

        @Test
        @DisplayName("S3 가 Content-Type 을 주지 않아도 head 가 깨지지 않는다")
        void headToleratesMissingContentType() {
            when(s3.headObject(any(HeadObjectRequest.class))).thenReturn(
                    HeadObjectResponse.builder().contentLength(4096L).build());

            assertThat(storage.head(ORIGINAL_KEY).contentType()).isEqualTo("application/octet-stream");
        }

        @Test
        @DisplayName("없는 키의 head 는 404다 — 아직 올라오지 않았다는 뜻이다")
        void headOnMissingKeyIsNotFound() {
            when(s3.headObject(any(HeadObjectRequest.class)))
                    .thenThrow(NoSuchKeyException.builder().message("no such key").build());

            assertThatThrownBy(() -> storage.head(ORIGINAL_KEY))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        }

        /** HeadObject 는 본문이 없어 없는 키에도 상태 코드만 오고 NoSuchKeyException 이 아닐 수 있다. */
        @Test
        @DisplayName("404 상태만 오는 head 실패도 404로 다룬다")
        void headOn404StatusIsNotFound() {
            when(s3.headObject(any(HeadObjectRequest.class))).thenThrow(
                    S3Exception.builder().statusCode(404).message("Not Found").build());

            assertThatThrownBy(() -> storage.head(ORIGINAL_KEY))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        }

        @Test
        @DisplayName("그 밖의 head 실패는 503이다")
        void headOtherFailureIsServiceUnavailable() {
            when(s3.headObject(any(HeadObjectRequest.class)))
                    .thenThrow(S3Exception.builder().statusCode(500).message("boom").build());

            assertThatThrownBy(() -> storage.head(ORIGINAL_KEY))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        }

        @Test
        @DisplayName("store 한 바이트를 read 가 그대로 돌려준다")
        void storeReadRoundTrip() {
            byte[] payload = "이미지 바이트".getBytes(StandardCharsets.UTF_8);
            when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                    ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), payload));

            AccidentImageStoragePort.StoredObject stored = storage.store(
                    new AccidentImageStoragePort.StoreImage(THUMBNAIL_KEY, payload, "image/jpeg"));

            assertThat(stored.size()).isEqualTo(payload.length);
            assertThat(storage.read(THUMBNAIL_KEY)).isEqualTo(payload);
        }

        @Test
        @DisplayName("없는 키의 read 는 404다 — 503이 아니다")
        void readOnMissingKeyIsNotFound() {
            when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                    .thenThrow(NoSuchKeyException.builder().message("no such key").build());

            assertThatThrownBy(() -> storage.read(ORIGINAL_KEY))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        }

        @Test
        @DisplayName("그 밖의 read 실패는 503이고 메시지에 키가 새지 않는다")
        void readOtherFailureIsServiceUnavailable() {
            when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                    .thenThrow(S3Exception.builder().message("boom").build());

            assertThatThrownBy(() -> storage.read(ORIGINAL_KEY))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                        assertThat(e.getMessage()).doesNotContain(ORIGINAL_KEY);
                    });
        }
    }

    // ------------------------------------------------------------------ 삭제 (멱등)

    @Nested
    @DisplayName("삭제 — 멱등이어야 한다")
    class Delete {

        @Test
        @DisplayName("원본 삭제는 staging 버킷을 향한다")
        void deleteTargetsStagingForOriginal() {
            storage.delete(ORIGINAL_KEY);

            ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
            verify(s3).deleteObject(request.capture());
            assertThat(request.getValue().bucket()).isEqualTo(STAGING);
            assertThat(request.getValue().key()).isEqualTo(ORIGINAL_KEY);
        }

        /**
         * 삭제 흐름은 완료 통보 전 업로드분까지 지우려고 결정적 키를 함께 넘기므로
         * <b>존재하지 않는 키가 정상적으로 들어온다.</b> 여기서 예외를 던지면 흐름이 통째로 깨진다.
         */
        @Test
        @DisplayName("없는 키를 지워도 예외를 던지지 않는다")
        void deleteIsIdempotent() {
            when(s3.deleteObject(any(DeleteObjectRequest.class)))
                    .thenThrow(NoSuchKeyException.builder().message("no such key").build());

            assertThatCode(() -> storage.delete(ORIGINAL_KEY)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("deleteAll 은 일부가 없어도 전체가 성공한다")
        void deleteAllToleratesMissingKeys() {
            when(s3.deleteObject(any(DeleteObjectRequest.class)))
                    .thenThrow(NoSuchKeyException.builder().message("no such key").build())
                    .thenReturn(null);

            assertThatCode(() -> storage.deleteAll(List.of(ORIGINAL_KEY, THUMBNAIL_KEY)))
                    .doesNotThrowAnyException();
            verify(s3, org.mockito.Mockito.times(2)).deleteObject(any(DeleteObjectRequest.class));
        }

        @Test
        @DisplayName("그 밖의 삭제 실패는 503이다 — 조용히 삼키지 않는다")
        void deleteOtherFailureIsServiceUnavailable() {
            when(s3.deleteObject(any(DeleteObjectRequest.class)))
                    .thenThrow(S3Exception.builder().message("boom").build());

            assertThatThrownBy(() -> storage.delete(ORIGINAL_KEY))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        }
    }

    // ------------------------------------------------------------------ 키 제약

    @Test
    @DisplayName("키 길이 상한을 넘기면 포트가 거절한다 — s3_key VARCHAR(500)")
    void keyLengthIsBounded() {
        String tooLong = "accidents/12/images/340/" + "a".repeat(500) + ".jpg";

        assertThatThrownBy(() -> new AccidentImageStoragePort.UploadUrlRequest(
                tooLong, "image/jpeg", 1024L, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(s3, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("requiredHeaders 는 불변이다")
    void requiredHeadersAreImmutable() {
        Map<String, String> headers = issue(1024L).requiredHeaders();

        assertThatThrownBy(() -> headers.put("X-Injected", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
