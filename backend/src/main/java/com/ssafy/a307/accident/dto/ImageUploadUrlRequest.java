package com.ssafy.a307.accident.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * {@code POST /api/accidents/{accidentId}/images/upload-urls} 요청.
 * <p>
 * 장수 상한은 {@code @Size} 로 박지 않는다 — 상한이 프로퍼티
 * ({@code app.accident-image.max-count-per-accident})이고, 판정도 <b>이미 등록된 장수와 합쳐</b>
 * 해야 하기 때문이다. 요청 하나만 보고 통과시키면 여러 번 나눠 보내 상한을 넘길 수 있다.
 * <p>
 * {@code s3Key} 를 받지 않는다. 키는 서버가 만든다 — 요청에 넣어도 무시된다.
 */
public record ImageUploadUrlRequest(
        @NotEmpty(message = "업로드할 파일 정보가 필요합니다.")
        @Valid
        List<ImageUploadUrlItem> files
) {

    public int count() {
        return files == null ? 0 : files.size();
    }
}
