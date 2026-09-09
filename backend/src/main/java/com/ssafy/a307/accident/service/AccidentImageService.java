package com.ssafy.a307.accident.service;

import com.ssafy.a307.accident.config.AccidentImageProperties;
import com.ssafy.a307.accident.dto.AccidentImageListResponse;
import com.ssafy.a307.accident.dto.AccidentImageResponse;
import com.ssafy.a307.accident.dto.AccidentImageResultResponse;
import com.ssafy.a307.accident.dto.ImageUploadCompleteItem;
import com.ssafy.a307.accident.dto.ImageUploadCompleteRequest;
import com.ssafy.a307.accident.dto.ImageUploadCompleteResponse;
import com.ssafy.a307.accident.dto.ImageUploadUrlItem;
import com.ssafy.a307.accident.dto.ImageUploadUrlRequest;
import com.ssafy.a307.accident.dto.ImageUploadUrlResponse;
import com.ssafy.a307.accident.dto.IssuedUploadUrl;
import com.ssafy.a307.accident.entity.Accident;
import com.ssafy.a307.accident.entity.AccidentImage;
import com.ssafy.a307.accident.entity.ImageVariant;
import com.ssafy.a307.accident.image.AccidentImageKeys;
import com.ssafy.a307.accident.image.AccidentImageStoragePort;
import com.ssafy.a307.accident.image.AccidentImageValidationException;
import com.ssafy.a307.accident.image.AccidentImageValidator;
import com.ssafy.a307.accident.image.ImageFormat;
import com.ssafy.a307.accident.image.ShootingAngleCodes;
import com.ssafy.a307.accident.repository.AccidentImageRepository;
import com.ssafy.a307.accident.repository.AccidentRepository;
import com.ssafy.a307.common.exception.BusinessException;
import com.ssafy.a307.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 사고 이미지 업로드 오케스트레이션 — URL 발급(Task 142) · 완료 통보(139·138) · 상태 조회(143) · 삭제.
 *
 * <p>차량·사고 도메인과 같은 방식이다 — {@code memberId} 를 첫 파라미터로 받고, 소유자 검사는
 * Repository 쿼리 조건에 넣는다. 남의 사고·없는 사고·소유가 아닌 이미지는 모두 404 다.
 *
 * <p><b>완료 통보 메서드에는 {@code @Transactional} 이 없다.</b> 이미지마다
 * {@link AccidentImageIngestService} 의 {@code REQUIRES_NEW} 트랜잭션이 따로 끊어져야 부분 실패가
 * 성립하기 때문이다. 바깥에 트랜잭션을 걸면 한 장의 실패가 전체를 오염시킨다.
 */
@Service
@RequiredArgsConstructor
public class AccidentImageService {

    private final AccidentRepository accidentRepository;
    private final AccidentImageRepository imageRepository;
    private final AccidentImageValidator validator;
    private final ShootingAngleCodes angleCodes;
    private final AccidentImageProperties properties;
    private final AccidentImageIngestService ingestService;
    private final Optional<AccidentImageStoragePort> storagePort;

    /**
     * 1단 제약 검증 후 파일별 presigned PUT URL 을 발급하고 {@code accident_image} 행을 예약한다.
     * <p>
     * 검증을 <b>전부 먼저</b> 한다. 한 건씩 검증하며 행을 만들면 세 번째 파일이 걸렸을 때
     * 앞의 두 행이 남는다(트랜잭션이 롤백하지만, 실패 시 사용자에게 "일부는 발급됨" 이라는
     * 애매한 상태를 설명해야 한다).
     */
    @Transactional
    public ImageUploadUrlResponse issueUploadUrls(
            Long memberId, Long accidentId, ImageUploadUrlRequest request) {

        Accident accident = ownedAccident(memberId, accidentId);
        AccidentImageStoragePort storage = storage();
        long alreadyRegistered = imageRepository.countByAccidentId(accidentId);

        List<ImageFormat> formats = new ArrayList<>(request.count());
        try {
            validator.validateCount(alreadyRegistered, request.count());
            for (ImageUploadUrlItem item : request.files()) {
                angleCodes.validate(item.angleCode());
                formats.add(validator.validateDeclared(
                        item.originalFilename(), item.contentType(), item.size()));
            }
        } catch (AccidentImageValidationException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, e.getMessage());
        }

        List<IssuedUploadUrl> issued = new ArrayList<>(request.count());
        for (int i = 0; i < request.count(); i++) {
            ImageUploadUrlItem item = request.files().get(i);
            ImageFormat format = formats.get(i);

            // 키에 imageId 가 들어가므로 행을 먼저 만들어 ID 를 받는다.
            AccidentImage image = imageRepository.saveAndFlush(
                    AccidentImage.reserve(accident, item.originalFilename()));
            String key = AccidentImageKeys.key(
                    accidentId, image.getImageId(), ImageVariant.ORIGINAL, format.canonicalExtension());

            // 상한이 아니라 이 파일의 신고 크기를 넘긴다. 어댑터가 그 값을 presigned 에 정확값으로
            // 서명하므로, 상한(20MB)을 넘기면 모든 업로드가 정확히 20MB 여야 하는 셈이 된다.
            // 신고값은 바로 위 validateDeclared 가 이미 상한과 대조한 뒤다.
            AccidentImageStoragePort.PresignedUpload upload = storage.createPresignedUploadUrl(
                    new AccidentImageStoragePort.UploadUrlRequest(
                            key,
                            format.contentType(),
                            item.size(),
                            properties.presignedUrlValidity()));

            issued.add(new IssuedUploadUrl(
                    image.getImageId(),
                    image.getOriginalFilename(),
                    item.angleCode(),
                    key,
                    upload.url().toString(),
                    "PUT",
                    upload.requiredHeaders(),
                    upload.expiresAt()));
        }

        long registered = alreadyRegistered + issued.size();
        return new ImageUploadUrlResponse(
                issued.size(),
                properties.maxCountPerAccident(),
                validator.remainingSlots(registered),
                issued);
    }

    /**
     * S3 PUT 을 마친 파일을 통보받아 3단 재검증 · 전처리 · asset 저장 · 품질 판정을 수행한다.
     * <p>
     * 요청 자체의 잘못(빈 목록 · 중복 imageId · 소유하지 않은 imageId)은 400·404 로 즉시 끊고,
     * <b>개별 이미지의 실패는 200 응답의 {@code results[].status = FAILED}</b> 로 내려간다.
     * 요구사항 22행이 "실패한 파일만 개별 재시도" 를 요구하므로 한 장의 실패로 전체를 뒤집으면
     * 나머지 성공분까지 사용자가 다시 올려야 한다.
     */
    public ImageUploadCompleteResponse complete(
            Long memberId, Long accidentId, ImageUploadCompleteRequest request) {

        ownedAccident(memberId, accidentId);
        storage();

        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (ImageUploadCompleteItem item : request.images()) {
            if (!uniqueIds.add(item.imageId())) {
                throw new BusinessException(
                        ErrorCode.INVALID_REQUEST,
                        "같은 이미지가 중복으로 통보되었습니다. (imageId %d)".formatted(item.imageId()));
            }
        }

        List<AccidentImage> owned = imageRepository.findAllOwnedByIds(accidentId, memberId, uniqueIds);
        if (owned.size() != uniqueIds.size()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "이미지를 찾을 수 없습니다.");
        }

        List<AccidentImageResultResponse> results = new ArrayList<>(request.images().size());
        for (ImageUploadCompleteItem item : request.images()) {
            String originalFilename = owned.stream()
                    .filter(image -> image.getImageId().equals(item.imageId()))
                    .map(AccidentImage::getOriginalFilename)
                    .findFirst()
                    .orElse(null);
            try {
                results.add(ingestService.ingest(accidentId, item.imageId(), memberId, item.size()));
            } catch (AccidentImageIngestFailedException e) {
                results.add(AccidentImageResultResponse.failed(
                        item.imageId(), originalFilename, e.getFailureCode(), e.getMessage()));
            }
        }
        return ImageUploadCompleteResponse.of(results);
    }

    /** 상태 조회(Task 143). 아직 완료 통보를 받지 못한 파일이 무엇인지 알려준다. */
    @Transactional(readOnly = true)
    public AccidentImageListResponse list(Long memberId, Long accidentId) {
        ownedAccident(memberId, accidentId);
        List<AccidentImageResponse> images = imageRepository.findAllOwned(accidentId, memberId).stream()
                .map(AccidentImageResponse::from)
                .toList();
        return AccidentImageListResponse.of(
                images, properties.maxCountPerAccident(), validator.remainingSlots(images.size()));
    }

    /**
     * 이미지 1장 삭제 — asset 전체와 저장소 오브젝트를 정리한 뒤 행을 지운다.
     * <p>
     * 완료 통보 전(asset 이 없는) 이미지도 저장소에 원본이 올라와 있을 수 있다. 키가 결정적이므로
     * 그 키도 함께 삭제 대상에 넣는다 — 그러지 않으면 취소된 업로드의 오브젝트가 남는다.
     * <p>
     * <b>API 명세서의 "분석 진행 중이면 409" 는 아직 구현하지 않았다.</b> {@code analysis_job} 을
     * 읽을 엔티티·레포지토리가 이 저장소에 없고, 그 테이블은 분석 Story 의 범위다(answer25 3장).
     */
    @Transactional
    public void delete(Long memberId, Long accidentId, Long imageId) {
        AccidentImage image = imageRepository.findOwned(accidentId, imageId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "이미지를 찾을 수 없습니다."));

        Set<String> keys = new LinkedHashSet<>();
        image.getAssets().forEach(asset -> keys.add(asset.getS3Key()));
        speculativeOriginalKey(accidentId, imageId, image.getOriginalFilename()).ifPresent(keys::add);
        if (!keys.isEmpty()) {
            storage().deleteAll(List.copyOf(keys));
        }

        imageRepository.delete(image);
        imageRepository.flush();
    }

    /** 완료 통보 전 업로드분까지 지우기 위한 결정적 원본 키. 확장자를 못 읽으면 비어 있다. */
    private Optional<String> speculativeOriginalKey(
            Long accidentId, Long imageId, String originalFilename) {
        int dot = originalFilename == null ? -1 : originalFilename.lastIndexOf('.');
        if (dot < 0) return Optional.empty();
        return ImageFormat.ofExtension(originalFilename.substring(dot + 1).toLowerCase(Locale.ROOT))
                .map(format -> AccidentImageKeys.key(
                        accidentId, imageId, ImageVariant.ORIGINAL, format.canonicalExtension()));
    }

    private Accident ownedAccident(Long memberId, Long accidentId) {
        return accidentRepository.findByAccidentIdAndMemberId(accidentId, memberId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "사고를 찾을 수 없습니다."));
    }

    private AccidentImageStoragePort storage() {
        return storagePort.orElseThrow(() -> new BusinessException(
                ErrorCode.SERVICE_UNAVAILABLE,
                "이미지 저장소 공급자가 구성되지 않았습니다."));
    }
}
