package com.ssafy.a307.estimatevalidation.file;

import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * 견적서 문서를 <b>로컬 파일시스템</b>에 저장하는 어댑터. 개발·데모용이다.
 *
 * <p><b>왜 로컬인가</b> — 버킷·IAM 없이 파일 업로드 경로를 끝까지 돌려 보기 위해서다.
 * {@code ck_ev_file} 이 "파일 입력이면 {@code s3_key_file} 이 NOT NULL" 을 강제하므로
 * 저장소를 건너뛸 수 없다. 운영용 구현은 {@link S3DocumentStorage} 다.
 *
 * <p><b>S3 어댑터와 동시에 뜨지 않는다.</b> 둘 다 {@code app.document-storage.provider} 하나로
 * 고르고 값이 각각 {@code local}·{@code s3} 이므로 배타가 구조로 보장된다. 둘이 함께 뜨면
 * 소비자의 {@code Optional<DocumentStoragePort>} 주입이 빈 두 개를 만나 기동이 실패한다.
 *
 * <p><b>프로퍼티가 없으면 이 빈은 뜨지 않는다.</b> 기본값을 코드에 두지 않은 것은 의도다
 * ({@code AccidentImageProperties} 와 같은 원칙). 빈이 없으면 소비자가 받는
 * {@code Optional} 이 비고, 기존 503 동작이 그대로 유지된다.
 *
 * <p><b>단일 인스턴스 전제다.</b> 로컬 디스크는 인스턴스마다 다르므로, 워커가 도는 서버와 파일을
 * 받은 서버가 다르면 읽지 못한다. 운영에서는 S3 로 바꿔야 한다 — 이 한계를 알고 쓰라는 뜻에서
 * 프로퍼티 이름에 {@code local} 을 박아 두었다.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.document-storage", name = "provider", havingValue = "local")
public class LocalDocumentStorageAdapter implements DocumentStoragePort {

    private static final String PDF_CONTENT_TYPE = "application/pdf";

    private final Path root;

    public LocalDocumentStorageAdapter(@Value("${app.document-storage.local.root}") Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("문서 저장소 루트를 만들 수 없습니다: " + this.root, e);
        }
        log.info("문서 저장소: 로컬 파일시스템 (root={})", this.root);
    }

    @Override
    public StoredDocument store(StoreDocument request) {
        byte[] content = request.content();
        String key = EstimateDocumentKeys.documentKey(request.extension(), Instant.now());
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw unavailable("견적서 파일을 저장하지 못했습니다.", e);
        }
        return new StoredDocument(key, content.length, request.contentType());
    }

    /**
     * 검증 결과 PDF 를 저장한다. 키 규칙만 다르고 쓰는 방식은 {@link #store} 와 같다
     * ({@link EstimateDocumentKeys#reportKey}).
     */
    @Override
    public StoredDocument storeReport(long validationId, byte[] pdfContent) {
        if (pdfContent == null || pdfContent.length == 0) {
            throw new IllegalArgumentException("pdfContent must not be empty");
        }
        String key = EstimateDocumentKeys.reportKey(validationId, Instant.now());
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, pdfContent);
        } catch (IOException e) {
            throw unavailable("검증 결과 PDF를 저장하지 못했습니다.", e);
        }
        return new StoredDocument(key, pdfContent.length, PDF_CONTENT_TYPE);
    }

    @Override
    public byte[] read(String storageKey) {
        Path target = resolve(storageKey);
        try {
            return Files.readAllBytes(target);
        } catch (NoSuchFileException e) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "견적서 파일을 찾을 수 없습니다.");
        } catch (IOException e) {
            throw unavailable("견적서 파일을 읽지 못했습니다.", e);
        }
    }

    /**
     * <b>멱등하다.</b> 없는 키를 지우라는 요청은 성공으로 취급한다 —
     * {@code AccidentImageStoragePort.delete} 와 같은 규약이고, 삭제 흐름이 PDF 키처럼
     * 아직 만들어지지 않은 키를 함께 넘기기 때문이다.
     */
    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            throw unavailable("견적서 파일을 삭제하지 못했습니다.", e);
        }
    }

    /**
     * <b>가짜 URL 을 만들지 않는다.</b> 로컬 파일시스템에는 서명된 임시 URL 이라는 개념이 없다.
     * 여기서 {@code file://} 이나 임시 링크를 지어내면 FE 가 열리지 않는 URL 을 받고
     * "구현됐다" 는 착각만 남는다 — 사용자에게 열리지 않는 링크를 주는 것이 503 보다 나쁘다.
     * 그래서 명확히 거절한다. 다운로드가 필요하면 S3 어댑터를 쓴다.
     *
     * <p>3인자 시그니처만 구현하면 된다 — 2인자 오버로드는 포트가 {@code default} 로 위임한다.
     */
    @Override
    public URI createPresignedDownloadUrl(String storageKey, Duration validity, String downloadFilename) {
        throw new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE,
                "로컬 문서 저장소는 다운로드 URL 발급을 지원하지 않습니다.");
    }

    /**
     * 키를 저장 루트 안의 경로로 바꾼다. <b>루트 밖으로 나가는 키는 거절한다.</b>
     *
     * <p>키는 서버가 만들지만({@link EstimateDocumentKeys}) 이 메서드는 DB 에 저장된 값을 받아
     * 다시 푼다. 저장 이후에 값이 어떤 경로로든 바뀌었다면 {@code ../../} 하나로 서버의 아무
     * 파일이나 읽히므로, 경계에서 한 번 더 막는다. 심볼릭 링크로 루트를 빠져나가는 경우까지
     * 보려고 실제 경로({@code toRealPath})도 확인한다.
     */
    private Path resolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new IllegalArgumentException("storageKey is required");
        }
        if (storageKey.startsWith("/") || storageKey.startsWith("\\") || storageKey.contains(":")) {
            throw rejectedKey();
        }
        Path candidate = root.resolve(storageKey).normalize();
        if (!candidate.startsWith(root)) {
            throw rejectedKey();
        }
        try {
            Path real = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!real.startsWith(root.toRealPath())) {
                throw rejectedKey();
            }
        } catch (IOException notCreatedYet) {
            // 아직 없는 파일은 실제 경로를 못 구한다. 위의 정규화 검사로 충분하다.
        }
        return candidate;
    }

    private static BusinessException rejectedKey() {
        return new BusinessException(ErrorCode.INVALID_REQUEST, "허용되지 않는 저장소 키입니다.");
    }

    /** 원인 예외는 로그로만 남긴다. 메시지에 실제 경로가 섞여 사용자에게 나가지 않게 한다. */
    private BusinessException unavailable(String message, IOException cause) {
        log.error("{} (root={})", message, root, cause);
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, message);
    }
}
