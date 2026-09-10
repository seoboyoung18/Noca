package com.ssafy.a307.accident.image;

import com.ssafy.a307.accident.config.AccidentImageProperties;
import com.ssafy.a307.accident.dto.AccidentImageAssetResponse;
import com.ssafy.a307.accident.entity.AccidentImage;
import com.ssafy.a307.accident.entity.AccidentImageAsset;
import com.ssafy.a307.accident.entity.ImageVariant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * asset 행을 <b>조회 가능한 응답</b>으로 바꾼다 — 화면에 나갈 variant 만 고르고 presigned GET URL 을 붙인다.
 *
 * <p><b>왜 별도 컴포넌트인가</b> — {@code AccidentImageService.list} 와
 * {@code AccidentImageIngestService.ingest} 두 곳이 같은 응답을 만든다. 한쪽에만 URL 을 붙이면
 * 완료 통보 직후에는 이미지가 안 보이고 목록을 다시 불러야 보이는 식으로 어긋난다. 두 서비스가
 * 서로를 부르게 만들 수는 없으므로(트랜잭션 경계가 다르다) 변환만 여기로 모은다.
 *
 * <p><b>{@code ORIGINAL} 은 목록에서 뺀다.</b> {@code S15P21A307-388} 의 결정이고, 원본은
 * staging 버킷에서 EXIF(GPS·기기 정보)를 단 채 7일만 살다 사라진다. 어댑터도 원본 키에는 조회
 * URL 을 발급하지 않으므로 방어선이 둘이다.
 *
 * <p><b>서명 실패는 그 asset 만 {@code url = null} 로 떨어뜨린다.</b> 전체 요청을 500 으로
 * 뒤집지 않는다 — 완료 통보가 이미지 한 장의 실패로 나머지를 버리지 않는 것과 같은 정책이다.
 * 화면은 파일명·상태·크기를 그대로 그리고 이미지 자리만 비운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccidentImageDownloadUrls {

    /** 화면에 내보내는 variant. 순서가 응답 순서다 — 큰 것 먼저 두면 목록이 항상 같은 모양이다. */
    private static final List<ImageVariant> EXPOSED = List.of(ImageVariant.RESIZED, ImageVariant.THUMBNAIL);

    private final AccidentImageProperties properties;
    private final Optional<AccidentImageStoragePort> storagePort;

    /**
     * 이미지 한 장의 노출 asset 목록.
     *
     * <p>저장소 어댑터가 없으면(버킷 미설정 로컬·테스트) URL 없이 메타만 돌려준다. 여기서 503 을
     * 던지면 <b>목록 조회 자체가 죽는다</b> — 지금 이 API 는 저장소 없이도 동작하는 유일한
     * 이미지 엔드포인트이고, 그 성질을 깨면 FE 가 업로드 진행률조차 볼 수 없다.
     */
    public List<AccidentImageAssetResponse> exposedAssets(AccidentImage image) {
        List<AccidentImageAssetResponse> responses = new ArrayList<>(EXPOSED.size());
        for (ImageVariant variant : EXPOSED) {
            image.asset(variant).map(this::toResponse).ifPresent(responses::add);
        }
        return List.copyOf(responses);
    }

    private AccidentImageAssetResponse toResponse(AccidentImageAsset asset) {
        return storagePort
                .map(storage -> withUrl(storage, asset))
                .orElseGet(() -> AccidentImageAssetResponse.withoutUrl(asset));
    }

    private AccidentImageAssetResponse withUrl(AccidentImageStoragePort storage, AccidentImageAsset asset) {
        try {
            AccidentImageStoragePort.PresignedDownload download =
                    storage.createPresignedDownloadUrl(asset.getS3Key(), properties.downloadUrlValidity());
            return AccidentImageAssetResponse.of(asset, download.url().toString(), download.expiresAt());
        } catch (RuntimeException e) {
            // 키는 로그에만. 응답 메시지로 나가면 안 되는 값이다.
            log.warn("조회 URL 발급 실패 assetId={} key={}", asset.getAssetId(), asset.getS3Key(), e);
            return AccidentImageAssetResponse.withoutUrl(asset);
        }
    }
}
