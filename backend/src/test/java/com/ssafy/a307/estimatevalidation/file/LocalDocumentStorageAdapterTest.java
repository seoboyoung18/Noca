package com.ssafy.a307.estimatevalidation.file;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 로컬 문서 저장소 어댑터. <b>실제 파일 I/O 로 검증한다</b> — 경로 탈출 차단과 삭제 멱등성은
 * 목으로 흉내내면 검증한 것이 아니다.
 */
@DisplayName("로컬 문서 저장소 어댑터")
class LocalDocumentStorageAdapterTest {

    @TempDir
    Path root;

    private LocalDocumentStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new LocalDocumentStorageAdapter(root);
    }

    @Test
    @DisplayName("저장하면 서버가 만든 키를 돌려주고 그 키로 같은 바이트를 읽는다")
    void storesAndReadsBack() {
        byte[] content = "%PDF-1.7 견적서".getBytes(StandardCharsets.UTF_8);

        DocumentStoragePort.StoredDocument stored = adapter.store(
                new DocumentStoragePort.StoreDocument(content, "application/pdf", "pdf"));

        assertThat(stored.storageKey()).startsWith("estimates/").endsWith(".pdf");
        assertThat(stored.size()).isEqualTo(content.length);
        assertThat(stored.contentType()).isEqualTo("application/pdf");
        assertThat(adapter.read(stored.storageKey())).isEqualTo(content);
    }

    @Test
    @DisplayName("원본 파일명은 키에 들어가지 않는다")
    void keyNeverCarriesUserInput() {
        DocumentStoragePort.StoredDocument stored = adapter.store(
                new DocumentStoragePort.StoreDocument(new byte[]{1, 2, 3}, "image/png", "png"));

        assertThat(stored.storageKey()).doesNotContain("견적서").doesNotContain("estimate.png");
        assertThat(stored.storageKey()).matches("estimates/\\d{4}/\\d{2}/[0-9a-f]{32}\\.png");
    }

    @Test
    @DisplayName("같은 확장자를 두 번 저장해도 키가 겹치지 않는다")
    void keysAreUnique() {
        String first = adapter.store(
                new DocumentStoragePort.StoreDocument(new byte[]{1}, "image/png", "png")).storageKey();
        String second = adapter.store(
                new DocumentStoragePort.StoreDocument(new byte[]{2}, "image/png", "png")).storageKey();

        assertThat(first).isNotEqualTo(second);
        assertThat(adapter.read(first)).containsExactly(1);
        assertThat(adapter.read(second)).containsExactly(2);
    }

    @Test
    @DisplayName("없는 키를 읽으면 404다 — 서버 경로가 메시지에 새지 않는다")
    void readingMissingKeyIsNotFound() {
        assertThatThrownBy(() -> adapter.read("estimates/2026/09/deadbeef.pdf"))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(error.getMessage()).doesNotContain(root.toString());
                });
    }

    @Test
    @DisplayName("삭제는 멱등하다 — 없는 키를 지워도 성공이다")
    void deleteIsIdempotent() {
        String key = adapter.store(
                new DocumentStoragePort.StoreDocument(new byte[]{7}, "application/pdf", "pdf")).storageKey();

        adapter.delete(key);
        assertThatCode(() -> adapter.delete(key)).doesNotThrowAnyException();
        assertThatCode(() -> adapter.deleteAll(List.of(key, "estimates/2026/01/none.pdf")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> adapter.read(key))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("presigned 다운로드 URL 은 가짜로 만들지 않고 503으로 거절한다")
    void presignedDownloadIsRefusedRatherThanFaked() {
        assertThatThrownBy(() -> adapter.createPresignedDownloadUrl("estimates/2026/09/a.pdf", Duration.ofMinutes(10)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
    }

    @Nested
    @DisplayName("경로 탈출 차단")
    class PathTraversal {

        @Test
        @DisplayName("상위 디렉터리로 나가는 키는 거절한다")
        void rejectsParentEscape() throws IOException {
            Path secret = root.getParent().resolve("secret.txt");
            Files.writeString(secret, "탈출하면 읽히는 파일");

            assertThatThrownBy(() -> adapter.read("../" + secret.getFileName()))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
            assertThatThrownBy(() -> adapter.read("estimates/../../secret.txt"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("절대경로와 드라이브 문자를 거절한다")
        void rejectsAbsolutePaths() {
            assertThatThrownBy(() -> adapter.read("/etc/passwd"))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> adapter.read("C:/Windows/win.ini"))
                    .isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> adapter.delete("\\\\server\\share\\x"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("빈 키를 거절한다")
        void rejectsBlankKey() {
            assertThatThrownBy(() -> adapter.read(" "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("키 생성 규칙")
    class KeyRules {

        @Test
        @DisplayName("확장자에 경로 문자가 있으면 키를 만들지 않는다")
        void rejectsPathCharactersInExtension() {
            Instant now = Instant.parse("2026-09-09T00:00:00Z");
            assertThatThrownBy(() -> EstimateDocumentKeys.documentKey("../pdf", now))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> EstimateDocumentKeys.documentKey("p/df", now))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> EstimateDocumentKeys.documentKey(" ", now))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("연·월 접두어는 한국 날짜를 따른다")
        void prefixUsesKoreanDate() {
            // UTC 로는 2026-08-31, 한국시각으로는 2026-09-01 인 순간.
            Instant boundary = Instant.parse("2026-08-31T15:30:00Z");

            assertThat(EstimateDocumentKeys.documentKey("pdf", boundary)).startsWith("estimates/2026/09/");
        }

        @Test
        @DisplayName("키는 s3_key_file VARCHAR(500) 안에 들어간다")
        void keyFitsColumn() {
            String key = EstimateDocumentKeys.documentKey("jpeg", Instant.now());

            assertThat(key.length()).isLessThanOrEqualTo(EstimateDocumentKeys.MAX_KEY_LENGTH);
        }
    }
}
