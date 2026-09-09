package com.ssafy.a307.estimatevalidation.file;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S3 문서 어댑터. <b>실제 AWS 를 부르지 않는다</b> — SDK 클라이언트를 목으로 주고
 * 요청 객체가 맞게 만들어지는지, 예외가 어떤 HTTP 코드로 바뀌는지만 본다.
 * 자격증명·네트워크가 없는 CI 에서 깨지면 안 된다.
 *
 * <p>버킷 이름은 더미다. 실제 버킷 이름·리전·계정 정보를 테스트에 적지 않는다.
 */
@DisplayName("S3 문서 저장소 어댑터")
class S3DocumentStorageTest {

    private static final String BUCKET = "test-bucket";

    private S3Client s3;
    private S3Presigner presigner;
    private S3DocumentStorage storage;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        presigner = mock(S3Presigner.class);
        storage = new S3DocumentStorage(s3, presigner, BUCKET);
    }

    // ------------------------------------------------------------------ 저장

    @Test
    @DisplayName("원본을 저장하면 서버가 만든 키로 올리고 그 키를 돌려준다")
    void storePutsWithServerGeneratedKey() {
        var content = "견적서".getBytes(StandardCharsets.UTF_8);

        var stored = storage.store(new DocumentStoragePort.StoreDocument(content, "application/pdf", "pdf"));

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo(stored.storageKey());
        assertThat(request.getValue().contentType()).isEqualTo("application/pdf");
        assertThat(stored.storageKey()).startsWith("estimates/").endsWith(".pdf");
        assertThat(stored.size()).isEqualTo(content.length);
    }

    @Test
    @DisplayName("원본 파일명은 키에 들어가지 않는다 — 사용자 입력이 저장소 경로에 섞이면 안 된다")
    void keyNeverCarriesOriginalFilename() {
        var stored = storage.store(new DocumentStoragePort.StoreDocument(
                new byte[] {1, 2, 3}, "image/jpeg", "jpg"));

        assertThat(stored.storageKey()).doesNotContain("견적서").doesNotContain("..");
    }

    @Test
    @DisplayName("PDF 리포트 키에는 검증 번호가 들어가고 reports 접두어 아래에 놓인다")
    void storeReportUsesReportKey() {
        var stored = storage.storeReport(42L, new byte[] {'%', 'P', 'D', 'F'});

        ArgumentCaptor<PutObjectRequest> request = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(request.capture(), any(RequestBody.class));
        assertThat(request.getValue().contentType()).isEqualTo("application/pdf");
        assertThat(stored.storageKey())
                .startsWith("estimates/reports/")
                .contains("/42-")
                .endsWith(".pdf");
    }

    @Test
    @DisplayName("빈 PDF 를 저장하지 않는다 — 내려줄 것이 없는 완료가 된다")
    void storeReportRejectsEmptyContent() {
        assertThatThrownBy(() -> storage.storeReport(1L, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("저장이 실패하면 503이고 예외 메시지에 키가 새지 않는다")
    void storeFailureBecomesServiceUnavailable() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("boom").build());

        assertThatThrownBy(() -> storage.store(new DocumentStoragePort.StoreDocument(
                new byte[] {1}, "application/pdf", "pdf")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    // ------------------------------------------------------------------ 읽기

    @Nested
    @DisplayName("읽기 — 판독 어댑터가 파일에 닿는 유일한 통로")
    class Read {

        @Test
        @DisplayName("저장한 바이트를 그대로 돌려준다")
        void returnsBytes() {
            byte[] payload = "본문".getBytes(StandardCharsets.UTF_8);
            when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(
                    ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), payload));

            assertThat(storage.read("estimates/2026/09/abc.pdf")).isEqualTo(payload);

            ArgumentCaptor<GetObjectRequest> request = ArgumentCaptor.forClass(GetObjectRequest.class);
            verify(s3).getObjectAsBytes(request.capture());
            assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
            assertThat(request.getValue().key()).isEqualTo("estimates/2026/09/abc.pdf");
        }

        @Test
        @DisplayName("없는 키는 404다 — 다시 시도해도 없으므로 503이 아니다")
        void missingKeyIsNotFound() {
            when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                    .thenThrow(NoSuchKeyException.builder().message("no such key").build());

            assertThatThrownBy(() -> storage.read("estimates/2026/09/gone.pdf"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        }

        @Test
        @DisplayName("그 밖의 저장소 오류는 503이다")
        void otherFailureIsServiceUnavailable() {
            when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                    .thenThrow(S3Exception.builder().message("boom").build());

            assertThatThrownBy(() -> storage.read("estimates/2026/09/abc.pdf"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        }
    }

    // ------------------------------------------------------------------ 삭제·다운로드

    @Test
    @DisplayName("삭제는 설정된 버킷의 그 키를 지운다")
    void deleteTargetsConfiguredBucket() {
        storage.delete("estimates/2026/09/abc.pdf");

        ArgumentCaptor<DeleteObjectRequest> request = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3).deleteObject(request.capture());
        assertThat(request.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(request.getValue().key()).isEqualTo("estimates/2026/09/abc.pdf");
    }

    @Nested
    @DisplayName("presigned 다운로드")
    class Presign {

        @BeforeEach
        void stubPresigner() throws Exception {
            PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
            when(presigned.url()).thenReturn(URI.create("https://example.invalid/signed").toURL());
            when(presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presigned);
        }

        @Test
        @DisplayName("한글 파일명은 RFC 5987 로 인코딩해 Content-Disposition 에 싣는다")
        void encodesKoreanFilename() {
            String filename = EstimateDocumentKeys.downloadFilename(7L, Instant.now());

            storage.createPresignedDownloadUrl("estimates/reports/2026/09/7-a.pdf",
                    Duration.ofMinutes(10), filename);

            ArgumentCaptor<GetObjectPresignRequest> request =
                    ArgumentCaptor.forClass(GetObjectPresignRequest.class);
            verify(presigner).presignGetObject(request.capture());
            String disposition = request.getValue().getObjectRequest().responseContentDisposition();
            assertThat(disposition)
                    .startsWith("attachment; filename*=UTF-8''")
                    .doesNotContain("견적검증");   // 원시 한글이 헤더에 그대로 실리지 않는다

            // 인코딩만 하고 값을 바꾸지 않았는지 — 되돌리면 원래 파일명이 나온다
            String encoded = disposition.substring("attachment; filename*=UTF-8''".length());
            assertThat(URLDecoder.decode(encoded, StandardCharsets.UTF_8)).isEqualTo(filename);
        }

        @Test
        @DisplayName("파일명을 주지 않으면 Content-Disposition 을 붙이지 않는다")
        void omitsDispositionWhenNoFilename() {
            storage.createPresignedDownloadUrl("estimates/2026/09/abc.pdf", Duration.ofMinutes(10));

            ArgumentCaptor<GetObjectPresignRequest> request =
                    ArgumentCaptor.forClass(GetObjectPresignRequest.class);
            verify(presigner).presignGetObject(request.capture());
            assertThat(request.getValue().getObjectRequest().responseContentDisposition()).isNull();
        }

        @Test
        @DisplayName("서명된 URL 을 그대로 돌려준다")
        void returnsSignedUrl() {
            URI url = storage.createPresignedDownloadUrl(
                    "estimates/2026/09/abc.pdf", Duration.ofMinutes(10), null);

            assertThat(url).hasToString("https://example.invalid/signed");
        }
    }
}
