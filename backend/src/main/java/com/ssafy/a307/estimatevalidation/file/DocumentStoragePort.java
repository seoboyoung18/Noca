package com.ssafy.a307.estimatevalidation.file;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 견적서 도메인의 문서 저장소 경계 — <b>업로드된 원본</b>과 <b>생성된 검증 PDF</b> 를 함께 다룬다.
 *
 * <p><b>키는 어댑터가 만든다.</b> 호출자가 키를 넘기는 저장 메서드를 두지 않은 것이 의도다 —
 * 사용자 입력이 섞인 키가 들어오면 경로 조작을 매 구현마다 다시 막아야 한다
 * ({@link EstimateDocumentKeys} 가 규칙을 갖는다).
 *
 * <p><b>구현체는 설정이 있을 때만 붙는다.</b> 소비자는 {@code Optional<DocumentStoragePort>} 로
 * 받아 없으면 503 을 준다 — 설정 없는 로컬·테스트에서 AWS 를 요구하지 않기 위해서다
 * ({@code EstimateFileValidationService.storage()}). 가짜 성공이나 하드코딩 URL 을 만들지 않는다.
 *
 * <p><b>이 인터페이스는 세 갈래로 갈라져 있던 것을 합친 결과다.</b> 원본 업로드({@code develop}),
 * OCR 읽기({@code -269} 파이프라인), PDF 저장·파일명 지정 다운로드({@code -398} PDF) 가 각각
 * 같은 파일을 서로 모르게 고쳤다. 어느 쪽 기능도 지우지 않았다 —
 * {@link #read}가 없으면 OCR 이 파일에 닿을 수 없고, {@link #storeReport}가 없으면 PDF 를
 * 저장할 수 없다.
 */
public interface DocumentStoragePort {

    /** 업로드된 견적서 원본을 저장한다. 키는 어댑터가 만들어 돌려준다. */
    StoredDocument store(StoreDocument request);

    /**
     * 저장한 문서의 바이트를 그대로 읽는다. <b>OCR 어댑터가 파일을 받을 유일한 통로다</b> —
     * {@code EstimateOcrPort.OcrDocument} 는 {@code storageKey} 만 들고 다니므로
     * 이 메서드가 없으면 어댑터가 파일에 닿을 방법이 없다.
     *
     * <p>시그니처는 {@code AccidentImageStoragePort.read} 와 같게 맞췄다. 같은 문제를 이미
     * 그렇게 풀었으므로 어댑터를 쓰는 쪽이 두 포트를 다르게 기억할 이유가 없다.
     *
     * @throws com.ssafy.a307.common.exception.BusinessException 키가 없으면 {@code NOT_FOUND},
     *         저장소를 읽을 수 없으면 {@code SERVICE_UNAVAILABLE}
     */
    byte[] read(String storageKey);

    /**
     * 검증 결과 PDF 를 저장한다.
     *
     * <p>{@link #store} 와 나눈 이유는 <b>키 규칙이 다르기 때문</b>이다. 리포트 키에는
     * {@code validationId} 가 들어가 운영 중 사람이 오브젝트를 찾을 수 있어야 한다.
     * 그렇다고 호출자가 키를 만들게 하면 경로 조작을 각 구현이 다시 막아야 하므로,
     * <b>식별자만 받고 키는 어댑터가 만든다.</b>
     */
    StoredDocument storeReport(long validationId, byte[] pdfContent);

    void delete(String storageKey);

    default void deleteAll(List<String> storageKeys) {
        storageKeys.forEach(this::delete);
    }

    /** 파일명을 지정하지 않는 다운로드. 저장소가 정한 기본 이름으로 내려간다. */
    default URI createPresignedDownloadUrl(String storageKey, Duration validity) {
        return createPresignedDownloadUrl(storageKey, validity, null);
    }

    /**
     * @param downloadFilename 브라우저가 저장할 파일명. {@code Content-Disposition} 으로 나간다.
     *                         null 이면 지정하지 않는다. <b>사용자 입력을 그대로 넣지 않는다</b> —
     *                         헤더에 실리는 값이다 ({@link EstimateDocumentKeys#downloadFilename})
     */
    URI createPresignedDownloadUrl(String storageKey, Duration validity, String downloadFilename);

    record StoreDocument(byte[] content, String contentType, String extension) {
        public StoreDocument {
            content = content == null ? null : content.clone();
            Objects.requireNonNull(content, "content");
            if (content.length == 0) throw new IllegalArgumentException("content must not be empty");
            if (contentType == null || contentType.isBlank()) {
                throw new IllegalArgumentException("contentType is required");
            }
            if (extension == null || extension.isBlank()) {
                throw new IllegalArgumentException("extension is required");
            }
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    record StoredDocument(String storageKey, long size, String contentType) {
        public StoredDocument {
            if (storageKey == null || storageKey.isBlank()) {
                throw new IllegalArgumentException("storageKey is required");
            }
            if (size < 1) throw new IllegalArgumentException("size must be positive");
            if (contentType == null || contentType.isBlank()) {
                throw new IllegalArgumentException("contentType is required");
            }
        }
    }
}
