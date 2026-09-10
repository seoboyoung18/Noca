package com.ssafy.a307.repaircase.image;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * AI-Hub 사례 이미지 저장소 경계. <b>읽기 전용이다.</b>
 *
 * <p><b>왜 조회 URL 발급 하나뿐인가</b> — 사례 이미지는 파이프라인이 미리 적재해 둔 정적
 * 데이터다. 서비스가 올리거나 지우는 경로가 없어, 사고 이미지처럼 업로드 presigned·변형본
 * 저장·삭제를 둘 이유가 없다. 지금 필요한 것만 둔다.
 *
 * <p><b>{@code AccidentImageStoragePort} 를 재사용하지 않았다.</b> 그쪽은 사고 이미지 경계이고
 * {@code ORIGINAL} 키에 조회 URL 을 발급하지 않는다 — EXIF 가 붙은 원본이 화면으로 나가는 것을
 * 막으려는 규칙이다. 사례 이미지는 정반대로 <b>{@code original} 만</b> 적재돼 있고 원천에서
 * 번호판이 흐림 처리돼 배포되므로, 그 규칙을 그대로 쓰면 아무 이미지도 못 내린다.
 *
 * <p><b>구현체는 버킷 설정이 있을 때만 붙는다.</b> 소비자는 {@code Optional} 로 받아 없으면
 * 이미지 없이 목록을 준다 — 버킷을 비워 둔 로컬·테스트에서 AWS 자격증명을 요구하지 않는다.
 */
public interface RepairCaseImageStoragePort {

    /**
     * 사례 이미지 조회 URL. 버킷이 비공개라 서명 없이는 읽을 수 없다.
     *
     * @param storageKey {@code repair_case_image.storage_key} 값.
     *                   {@code repair-cases/{caseId}/images/{caseImageId}/original.jpg}
     * @param validity   서명 유효시간
     */
    PresignedDownload createPresignedDownloadUrl(String storageKey, Duration validity);

    record PresignedDownload(URI url, Instant expiresAt) {
        public PresignedDownload {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }
}
