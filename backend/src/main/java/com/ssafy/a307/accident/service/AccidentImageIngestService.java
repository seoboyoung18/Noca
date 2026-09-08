package com.ssafy.a307.accident.service;

import com.ssafy.a307.accident.dto.AccidentImageResultResponse;
import com.ssafy.a307.accident.dto.ImageProcessingStatus;
import com.ssafy.a307.accident.entity.AccidentImage;
import com.ssafy.a307.accident.entity.AccidentImageAsset;
import com.ssafy.a307.accident.entity.ImageVariant;
import com.ssafy.a307.accident.image.AccidentImagePreprocessor;
import com.ssafy.a307.accident.image.AccidentImageKeys;
import com.ssafy.a307.accident.image.AccidentImageStoragePort;
import com.ssafy.a307.accident.image.AccidentImageValidationException;
import com.ssafy.a307.accident.image.AccidentImageValidator;
import com.ssafy.a307.accident.image.ImageFormat;
import com.ssafy.a307.accident.image.ImageQualityAssessor;
import com.ssafy.a307.accident.repository.AccidentImageRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 이미지 <b>한 장</b>의 완료 처리 — 3단 재검증 → EXIF 보정 → 변형본 생성·저장 → asset 저장 →
 * 품질 판정. Task 139 · 138 · 140(3단)이 여기서 만난다.
 *
 * <p>서비스를 둘로 나눈 이유는 <b>트랜잭션 경계</b>다. 요구사항 22행이 개별 재시도를 요구하므로
 * 20장 중 3장이 실패해도 나머지 17장은 남아야 한다. 그러려면 이미지마다 트랜잭션이 따로 끊어져야
 * 하고, 자기 자신을 호출하면 프록시를 거치지 않아 {@code REQUIRES_NEW} 가 먹지 않는다.
 * 그래서 별개 빈이다.
 *
 * <p>반대로 <b>한 장 안에서는 원자적</b>이다. {@code ORIGINAL} 은 있는데 {@code RESIZED} 가 없는
 * 중간 상태를 커밋하지 않는다 — asset 3행을 한 번에 저장하고, 어느 하나라도 실패하면 이 트랜잭션이
 * 통째로 롤백된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccidentImageIngestService {

    private final AccidentImageRepository imageRepository;
    private final AccidentImageValidator validator;
    private final AccidentImagePreprocessor preprocessor;
    private final ImageQualityAssessor qualityAssessor;

    /**
     * 어댑터가 없다. {@code EstimateFileValidationService} 와 같은 방식으로 {@code Optional} 로 받고,
     * 없으면 503 을 준다 — 가짜 성공이나 하드코딩 URL 을 만들지 않는다.
     */
    private final Optional<AccidentImageStoragePort> storagePort;

    /**
     * @param declaredSize 발급 때 신고한 크기. null 이면 크기 일치 검증을 건너뛴다
     * @throws AccidentImageIngestFailedException 이 이미지만 실패. 바깥 루프가 다음 장으로 넘어간다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AccidentImageResultResponse ingest(
            Long accidentId, Long imageId, Long memberId, Long declaredSize) {

        AccidentImage image = imageRepository.findOwned(accidentId, imageId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "이미지를 찾을 수 없습니다."));

        if (image.isUploadCompleted()) {
            return AccidentImageResultResponse.completed(image, ImageProcessingStatus.ALREADY_COMPLETED);
        }

        AccidentImageStoragePort storage = storage();
        ImageFormat format = format(image);
        String originalKey = AccidentImageKeys.key(
                accidentId, imageId, ImageVariant.ORIGINAL, format.canonicalExtension());

        // 실패 시 지울 대상. 원본은 검증 실패일 때만 지운다 — 서버 결함으로 처리가 깨진 경우까지
        // 원본을 날리면 사용자가 다시 올려야 한다.
        List<String> derivedKeys = new ArrayList<>(2);

        AccidentImageStoragePort.StoredObject stored = head(storage, originalKey, image);
        byte[] original = read(storage, originalKey, image);

        try {
            validator.validateStored(image.getOriginalFilename(), stored.size(), original, declaredSize);
        } catch (AccidentImageValidationException e) {
            storage.deleteAll(List.of(originalKey));
            throw failed(e.reason().name(), e.getMessage());
        }

        try {
            AccidentImagePreprocessor.Preprocessed processed = preprocessor.preprocess(original, format);

            AccidentImagePreprocessor.RenderedVariant resized = processed.resized();
            AccidentImagePreprocessor.RenderedVariant thumbnail = processed.thumbnail();
            String resizedKey = AccidentImageKeys.key(
                    accidentId, imageId, ImageVariant.RESIZED, resized.extension());
            String thumbnailKey = AccidentImageKeys.key(
                    accidentId, imageId, ImageVariant.THUMBNAIL, thumbnail.extension());

            storage.store(new AccidentImageStoragePort.StoreImage(
                    resizedKey, resized.content(), resized.contentType()));
            derivedKeys.add(resizedKey);
            storage.store(new AccidentImageStoragePort.StoreImage(
                    thumbnailKey, thumbnail.content(), thumbnail.contentType()));
            derivedKeys.add(thumbnailKey);

            image.addAsset(AccidentImageAsset.of(
                    ImageVariant.ORIGINAL, originalKey,
                    processed.width(), processed.height(), stored.size()));
            image.addAsset(AccidentImageAsset.of(
                    ImageVariant.RESIZED, resizedKey,
                    resized.width(), resized.height(), resized.content().length));
            image.addAsset(AccidentImageAsset.of(
                    ImageVariant.THUMBNAIL, thumbnailKey,
                    thumbnail.width(), thumbnail.height(), thumbnail.content().length));

            ImageQualityAssessor.Assessment assessment =
                    qualityAssessor.assess(processed.shortEdge(), resized.content());
            image.markQuality(assessment.status(), assessment.reason());

            imageRepository.saveAndFlush(image);
            return AccidentImageResultResponse.completed(image, ImageProcessingStatus.COMPLETED);

        } catch (AccidentImageValidationException e) {
            cleanup(storage, derivedKeys, originalKey);
            throw failed(e.reason().name(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("이미지 처리 실패 imageId={} key={}", imageId, originalKey, e);
            cleanup(storage, derivedKeys, null);
            throw failed("PROCESSING_ERROR", "이미지를 처리할 수 없습니다.");
        }
    }

    private ImageFormat format(AccidentImage image) {
        String filename = image.getOriginalFilename();
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ImageFormat.ofExtension(extension)
                .orElseThrow(() -> failed(
                        AccidentImageValidationException.Reason.UNSUPPORTED_EXTENSION.name(),
                        ImageFormat.supportedLabel() + " 파일만 업로드할 수 있습니다."));
    }

    private AccidentImageStoragePort.StoredObject head(
            AccidentImageStoragePort storage, String key, AccidentImage image) {
        try {
            return storage.head(key);
        } catch (RuntimeException e) {
            log.warn("업로드된 오브젝트를 찾을 수 없음 imageId={} key={}", image.getImageId(), key, e);
            throw failed(
                    AccidentImageValidationException.Reason.MISSING_FILE.name(),
                    "저장소에 업로드된 이미지가 없습니다. 업로드를 먼저 완료해 주세요.");
        }
    }

    private byte[] read(AccidentImageStoragePort storage, String key, AccidentImage image) {
        try {
            return storage.read(key);
        } catch (RuntimeException e) {
            log.warn("오브젝트 읽기 실패 imageId={} key={}", image.getImageId(), key, e);
            throw failed(
                    AccidentImageValidationException.Reason.MISSING_FILE.name(),
                    "업로드된 이미지를 읽을 수 없습니다.");
        }
    }

    /** 저장소 정리는 실패해도 원래 실패 사유를 덮지 않는다. */
    private void cleanup(
            AccidentImageStoragePort storage, List<String> derivedKeys, String originalKey) {
        List<String> keys = new ArrayList<>(derivedKeys);
        if (originalKey != null) keys.add(originalKey);
        if (keys.isEmpty()) return;
        try {
            storage.deleteAll(List.copyOf(keys));
        } catch (RuntimeException cleanupFailure) {
            log.warn("실패한 이미지의 저장소 정리 실패 keys={}", keys, cleanupFailure);
        }
    }

    private AccidentImageStoragePort storage() {
        return storagePort.orElseThrow(() -> new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE,
                "이미지 저장소 공급자가 구성되지 않았습니다."));
    }

    private AccidentImageIngestFailedException failed(String code, String message) {
        return new AccidentImageIngestFailedException(code, message);
    }
}
