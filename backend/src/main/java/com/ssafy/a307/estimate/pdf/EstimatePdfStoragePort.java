package com.ssafy.a307.estimate.pdf;

import java.net.URI;
import java.time.Duration;

/**
 * 견적 PDF 저장소 경계 (S15P21A307-342).
 *
 * <p><b>{@code DocumentStoragePort} 를 쓰지 않았다.</b> 그쪽의 PDF 저장은 검증 전용이라 키에
 * {@code validationId} 가 들어간다. 견적 PDF 를 담으려면 그 포트와 어댑터 둘·키 규칙을 함께 고쳐야
 * 해서, 다른 담당 영역을 건드리지 않고 같은 서비스 버킷을 쓰는 포트를 따로 둔다. 합치는 것은
 * 담당자와 협의할 일이다.
 *
 * <p><b>키는 어댑터가 만든다</b>({@link EstimatePdfKeys}). 호출자가 키를 넘기는 저장 통로가 없다.
 *
 * <p>구현체는 서비스 버킷이 설정됐을 때만 붙는다. 소비자는 {@code Optional} 로 받는다.
 */
public interface EstimatePdfStoragePort {

    StoredPdf store(String reportNo, byte[] pdfContent);

    /**
     * @param downloadFilename 브라우저가 저장할 이름. 서버가 만든 값만 넣는다 — 헤더에 실린다
     */
    URI createPresignedDownloadUrl(String storageKey, Duration validity, String downloadFilename);

    record StoredPdf(String storageKey, long size) {
        public StoredPdf {
            if (storageKey == null || storageKey.isBlank()) {
                throw new IllegalArgumentException("storageKey is required");
            }
            if (size < 1) {
                throw new IllegalArgumentException("size must be positive");
            }
        }
    }
}
